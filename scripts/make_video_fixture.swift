// Generates the synthetic 1080p H.264 MP4 used by video tests (#19): original
// content, no third-party media. macOS only (AVFoundation).
// Usage: swift scripts/make_video_fixture.swift <out.mp4> [width height seconds]
import AVFoundation
import CoreVideo

let args = CommandLine.arguments
let out = URL(fileURLWithPath: args.count > 1 ? args[1] : "supabase/tests/fixtures/video-1080p.mp4")
let width = args.count > 3 ? Int(args[2])! : 1920
let height = args.count > 3 ? Int(args[3])! : 1080
let seconds = args.count > 4 ? Int(args[4])! : 3
let fps = 15
try? FileManager.default.removeItem(at: out)
let writer = try AVAssetWriter(outputURL: out, fileType: .mp4)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height,
    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 600_000]])
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
    kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height])
writer.add(input)
writer.startWriting()
writer.startSession(atSourceTime: .zero)
for frame in 0..<(fps * seconds) {
    while !input.isReadyForMoreMediaData { usleep(1000) }
    var buffer: CVPixelBuffer?
    CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &buffer)
    CVPixelBufferLockBaseAddress(buffer!, [])
    let base = CVPixelBufferGetBaseAddress(buffer!)!.assumingMemoryBound(to: UInt8.self)
    let stride = CVPixelBufferGetBytesPerRow(buffer!)
    let bar = (frame * width) / (fps * seconds)
    for y in 0..<height {
        for x in 0..<width {
            let p = base + y * stride + x * 4
            p[0] = UInt8((x * 255) / width); p[1] = UInt8((y * 255) / height)
            p[2] = x < bar ? 230 : 40; p[3] = 255
        }
    }
    CVPixelBufferUnlockBaseAddress(buffer!, [])
    adaptor.append(buffer!, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: CMTimeScale(fps)))
}
input.markAsFinished()
let done = DispatchSemaphore(value: 0)
writer.finishWriting { done.signal() }
done.wait()
guard writer.status == .completed else { fatalError("\(String(describing: writer.error))") }
print("wrote \(out.path)")
