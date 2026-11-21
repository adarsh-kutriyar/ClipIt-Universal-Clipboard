import Foundation
import CoreBluetooth
import CryptoKit

public struct BleProtocol {
    public static let serviceUUID = CBUUID(string: "8D4F0001-3A5B-4C6D-9E8F-1B2C3D4E5F60")
    public static let clipboardCharUUID = CBUUID(string: "8D4F0002-3A5B-4C6D-9E8F-1B2C3D4E5F60")
    public static let advertisedName = "ClipIt-Mac"
    
    // Header size: 1 byte msgId + 2 bytes totalChunks + 2 bytes chunkIndex = 5 bytes
    public static let headerSize = 5
    
    public struct ChunkPacket {
        public let messageId: UInt8
        public let totalChunks: UInt16
        public let chunkIndex: UInt16
        public let payload: Data
        
        public func serialize() -> Data {
            var data = Data()
            data.append(messageId)
            
            var total = totalChunks.bigEndian
            withUnsafeBytes(of: &total) { data.append(contentsOf: $0) }
            
            var index = chunkIndex.bigEndian
            withUnsafeBytes(of: &index) { data.append(contentsOf: $0) }
            
            data.append(payload)
            return data
        }
        
        public static func deserialize(from data: Data) -> ChunkPacket? {
            guard data.count >= headerSize else { return nil }
            
            let msgId = data[0]
            
            let total = data.subdata(in: 1..<3).withUnsafeBytes { $0.load(as: UInt16.self).bigEndian }
            let index = data.subdata(in: 3..<5).withUnsafeBytes { $0.load(as: UInt16.self).bigEndian }
            let payload = data.subdata(in: 5..<data.count)
            
            return ChunkPacket(messageId: msgId, totalChunks: total, chunkIndex: index, payload: payload)
        }
    }
    
    public static func chunk(text: String, messageId: UInt8, maxPacketSize: Int = 180) -> [Data] {
        guard let utf8Data = text.data(using: .utf8) else { return [] }
        let effectivePayloadSize = max(10, maxPacketSize - headerSize)
        let totalCount = utf8Data.count
        
        if totalCount == 0 {
            let single = ChunkPacket(messageId: messageId, totalChunks: 1, chunkIndex: 0, payload: Data())
            return [single.serialize()]
        }
        
        let chunkCount = UInt16((totalCount + effectivePayloadSize - 1) / effectivePayloadSize)
        var packets: [Data] = []
        
        for i in 0..<chunkCount {
            let start = Int(i) * effectivePayloadSize
            let end = min(start + effectivePayloadSize, totalCount)
            let chunkData = utf8Data.subdata(in: start..<end)
            
            let packet = ChunkPacket(messageId: messageId, totalChunks: chunkCount, chunkIndex: i, payload: chunkData)
            packets.append(packet.serialize())
        }
        
        return packets
    }
    
    public class Reassembler {
        private struct MessageBuffer {
            let totalChunks: UInt16
            var receivedChunks: [UInt16: Data] = [:]
            var timestamp: Date = Date()
        }
        
        private var buffers: [UInt8: MessageBuffer] = [:]
        private let timeout: TimeInterval = 6.0
        
        public init() {}
        
        public func feed(packetData: Data) -> String? {
            cleanupStaleBuffers()
            
            guard let packet = ChunkPacket.deserialize(from: packetData) else {
                return nil
            }
            
            var buffer = buffers[packet.messageId] ?? MessageBuffer(totalChunks: packet.totalChunks)
            buffer.receivedChunks[packet.chunkIndex] = packet.payload
            buffer.timestamp = Date()
            buffers[packet.messageId] = buffer
            
            if buffer.receivedChunks.count == Int(buffer.totalChunks) {
                // Reconstruct
                var fullData = Data()
                for i in 0..<buffer.totalChunks {
                    if let part = buffer.receivedChunks[i] {
                        fullData.append(part)
                    } else {
                        // Missing chunk
                        return nil
                    }
                }
                buffers.removeValue(forKey: packet.messageId)
                return String(data: fullData, encoding: .utf8)
            }
            
            return nil
        }
        
        private func cleanupStaleBuffers() {
            let now = Date()
            buffers = buffers.filter { now.timeIntervalSince($0.value.timestamp) < timeout }
        }
    }
    
    public static func hash(text: String) -> String {
        let inputData = Data(text.utf8)
        let hashed = SHA256.hash(data: inputData)
        return hashed.compactMap { String(format: "%02x", $0) }.joined()
    }
}
