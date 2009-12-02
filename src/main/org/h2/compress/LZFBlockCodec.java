package org.h2.compress;

import java.io.*;

/**
 * Simple non-streaming compressor that can compress and uncompress
 * blocks of varying sizes efficiently.
 */
public final class LZFBlockCodec
{
    private static final int HASH_SIZE = (1 << 14); // 16k
    private static final int MAX_LITERAL = 1 << 5; // 32
    private static final int MAX_OFF = 1 << 13; // 8k
    private static final int MAX_REF = (1 << 8) + (1 << 3); // 264

    final static byte BYTE_NULL = 0;

    final static byte BYTE_Z = 'Z';
    final static byte BYTE_V = 'V';

    final static int BLOCK_TYPE_UNCOMPRESSED = 0;
    final static int BLOCK_TYPE_COMPRESSED = 1;

    public LZFBlockCodec() { }

    // // // Compress:

    // // // Decompress:

    /**
     * Method for decompressing whole input data, which encoded in LZF
     * block structure (compatible with lzf command line utility),
     * and can consist of any number of blocks
     */
    public byte[] decompress(byte[] data) throws IOException
    {
        /* First: let's calculate actual size, so we can allocate
         * exact result size. Also useful for basic sanity checking;
         * so that after call we know header structure is not corrupt
         * (to the degree that lengths etc seem valid)
         */
        byte[] result = new byte[calculateUncompressedSize(data)];
        int inPtr = 0;
        int outPtr = 0;

        while (inPtr < (data.length - 1)) { // -1 to offset possible end marker
            inPtr += 2; // skip 'ZV' marker
            int type = data[inPtr++];
            int len = uint16(data, inPtr);
            inPtr += 2;
            if (type == BLOCK_TYPE_UNCOMPRESSED) { // uncompressed
                System.arraycopy(data, inPtr, result, outPtr, len);
                outPtr += len;
            } else { // compressed
                int uncompLen = uint16(data, inPtr);
                inPtr += 2;
                decompressBlock(data, inPtr, result, outPtr, outPtr+uncompLen);
                outPtr += uncompLen;
            }
            inPtr += len;
        }
        return result;
    }

    /**
     * Main decompress method for individual blocks/chunks.
     */
    public static void decompressBlock(byte[] in, int inPos, byte[] out, int outPos, int outEnd)
        throws IOException
    {
        do {
            int ctrl = in[inPos++] & 255;
            if (ctrl < MAX_LITERAL) {
                // literal run
                ctrl += inPos;
                do {
                    out[outPos++] = in[inPos];
                } while (inPos++ < ctrl);
            } else {
                // back reference
                int len = ctrl >> 5;
                ctrl = -((ctrl & 0x1f) << 8) - 1;
                if (len == 7) {
                    len += in[inPos++] & 255;
                }
                ctrl -= in[inPos++] & 255;
                len += outPos + 2;
                out[outPos] = out[outPos++ + ctrl];
                out[outPos] = out[outPos++ + ctrl];
                while (outPos < len - 8) {
                    out[outPos] = out[outPos++ + ctrl];
                    out[outPos] = out[outPos++ + ctrl];
                    out[outPos] = out[outPos++ + ctrl];
                    out[outPos] = out[outPos++ + ctrl];
                    out[outPos] = out[outPos++ + ctrl];
                    out[outPos] = out[outPos++ + ctrl];
                    out[outPos] = out[outPos++ + ctrl];
                    out[outPos] = out[outPos++ + ctrl];
                }
                while (outPos < len) {
                    out[outPos] = out[outPos++ + ctrl];
                }
            }
        } while (outPos < outEnd);

        // sanity check to guard against corrupt data:
        if (outPos != outEnd) throw new IOException("Corrupt data: overrun in decompress, input offset "+inPos+", output offset "+outPos);
    }

    // // // Helper methods

    private int calculateUncompressedSize(byte[] data) throws IOException
    {
        int uncompressedSize = 0;
        int ptr = 0;
        int blockNr = 0;

        while (ptr < data.length) {
            // can use optional end marker
            if (ptr == (data.length + 1) && data[ptr] == BYTE_NULL) {
                ++ptr; // so that we'll be at end
                break;
            }
            // simpler to handle bounds checks by catching exception here...
            try {
                if (data[ptr] != BYTE_Z || data[ptr+1] != BYTE_V) {
                    throw new IOException("Corrupt input data, block #"+blockNr+" (at offset "+ptr+"): did not start with 'ZV' signature bytes");
                }
                int type = (int) data[ptr+2];
                int blockLen = uint16(data, ptr+3);
                if (type == BLOCK_TYPE_UNCOMPRESSED) { // uncompressed
                    ptr += 5;
                    uncompressedSize += blockLen;
                } else if (type == BLOCK_TYPE_COMPRESSED) { // compressed
                    uncompressedSize += uint16(data, ptr+5);
                    ptr += 7;
                } else { // unknown... CRC-32 would be 2, but that's not implemented by cli tool
                    throw new IOException("Corrupt input data, block #"+blockNr+" (at offset "+ptr+"): unrecognized block type "+(type & 0xFF));
                }
                ptr += blockLen;
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IOException("Corrupt input data, block #"+blockNr+" (at offset "+ptr+"): truncated block header");
            }
            ++blockNr;
        }
        // one more sanity check:
        if (ptr != data.length) {
            throw new IOException("Corrupt input data: block #"+blockNr+" extends "+(data.length - ptr)+" beyond end of input");
        }
        return uncompressedSize;
    }

    private static int uint16(byte[] data, int ptr)
    {
        return ((data[ptr] & 0xFF) << 8) + (data[ptr+1] & 0xFF);
    }
    
}
   
