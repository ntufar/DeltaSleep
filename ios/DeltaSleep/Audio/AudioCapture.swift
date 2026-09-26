import AVFoundation

/// Streams the microphone as 160-sample (10 ms @ 16 kHz) Int16 frames.
///
/// The session is play-and-record with mix-with-others, so podcasts and
/// white-noise apps keep playing. `.measurement` mode turns off the system
/// AGC/noise suppression, the iOS analogue of Android's VOICE_RECOGNITION
/// source, so dB thresholds in the DSP mean the same thing on both.
///
/// Raw PCM never leaves memory: each frame is handed to [onFrame] and the
/// buffer is reused.
final class AudioCapture {
    enum CaptureError: Error { case noInput, converterUnavailable }

    /// Called on the capture's processing queue with each 10 ms frame.
    var onFrame: ((UnsafeBufferPointer<Int16>) -> Void)?
    /// Called on the processing queue when ~2 s of pure zeros arrive
    /// (mic access lost) — the owner restarts capture.
    var onSilenceStall: (() -> Void)?

    let queue: DispatchQueue
    private let engine = AVAudioEngine()
    private let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: true)!
    private var converter: AVAudioConverter?
    private var pending: [Int16] = []
    private var silentFrames = 0
    private static let silentFrameLimit = 200

    init(queue: DispatchQueue = DispatchQueue(label: "deltasleep.audio", qos: .userInitiated)) {
        self.queue = queue
        pending.reserveCapacity(4 * DSP.frameSamples)
    }

    static func configureSession() throws {
        let s = AVAudioSession.sharedInstance()
        try s.setCategory(.playAndRecord, mode: .measurement,
                          options: [.mixWithOthers, .defaultToSpeaker, .allowBluetoothA2DP])
        try? s.setPreferredSampleRate(16_000)
        try? s.setPreferredIOBufferDuration(0.1)
        try s.setActive(true)
    }

    func start() throws {
        // Touching inputNode with no input route can abort inside CoreAudio;
        // fail softly instead and let the owner retry.
        guard AVAudioSession.sharedInstance().isInputAvailable else { throw CaptureError.noInput }
        let input = engine.inputNode
        let inFormat = input.outputFormat(forBus: 0)
        guard inFormat.sampleRate > 0, inFormat.channelCount > 0 else { throw CaptureError.noInput }
        guard let conv = AVAudioConverter(from: inFormat, to: target) else { throw CaptureError.converterUnavailable }
        converter = conv
        queue.sync { pending.removeAll(keepingCapacity: true); silentFrames = 0 }

        input.installTap(onBus: 0, bufferSize: AVAudioFrameCount(inFormat.sampleRate / 10), format: inFormat) { [weak self] buffer, _ in
            guard let self, let samples = self.convert(buffer) else { return }
            self.queue.async { self.emitFrames(samples) }
        }
        engine.prepare()
        try engine.start()
    }

    func stop() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
    }

    var isRunning: Bool { engine.isRunning }

    private func convert(_ buffer: AVAudioPCMBuffer) -> [Int16]? {
        guard let converter else { return nil }
        let ratio = target.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return nil }
        var fed = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if fed {
                status.pointee = .noDataNow
                return nil
            }
            fed = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, let ch = out.int16ChannelData else { return nil }
        return Array(UnsafeBufferPointer(start: ch[0], count: Int(out.frameLength)))
    }

    private func emitFrames(_ samples: [Int16]) {
        pending.append(contentsOf: samples)
        var offset = 0
        while pending.count - offset >= DSP.frameSamples {
            let range = offset..<(offset + DSP.frameSamples)
            if pending[range].allSatisfy({ $0 == 0 }) {
                silentFrames += 1
                if silentFrames >= Self.silentFrameLimit {
                    silentFrames = 0
                    onSilenceStall?()
                }
            } else {
                silentFrames = 0
            }
            pending.withUnsafeBufferPointer { buf in
                onFrame?(UnsafeBufferPointer(rebasing: buf[range]))
            }
            offset += DSP.frameSamples
        }
        pending.removeFirst(offset)
    }
}
