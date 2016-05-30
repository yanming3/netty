/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

/*
 * Copyright 2014 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.netty.handler.codec.http2.internal.hpack;

import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;
import io.netty.util.ByteProcessor;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;

final class HuffmanEncoder {

    private final int[] codes;
    private final byte[] lengths;
    private final FastThreadLocal<AsciiStringEncodedLength> encodedLength =
            new FastThreadLocal<AsciiStringEncodedLength>() {
        @Override
        protected AsciiStringEncodedLength initialValue() {
            return new AsciiStringEncodedLength();
        }
    };
    private final FastThreadLocal<AsciiStringEncoder> encoder = new FastThreadLocal<AsciiStringEncoder>() {
        @Override
        protected AsciiStringEncoder initialValue() {
            return new AsciiStringEncoder();
        }
    };

    /**
     * Creates a new Huffman encoder with the specified Huffman coding.
     *
     * @param codes the Huffman codes indexed by symbol
     * @param lengths the length of each Huffman code
     */
    HuffmanEncoder(int[] codes, byte[] lengths) {
        this.codes = codes;
        this.lengths = lengths;
    }

    /**
     * Compresses the input string literal using the Huffman coding.
     *
     * @param out the output stream for the compressed data
     * @param data the string literal to be Huffman encoded
     */
    public void encode(ByteBuf out, CharSequence data) {
        ObjectUtil.checkNotNull(out, "out");
        if (data instanceof AsciiString) {
            encoder.get().encode(out, (AsciiString) data);
        } else {
            // slow path
            long current = 0;
            int n = 0;

            for (int i = 0; i < data.length(); i++) {
                int b = data.charAt(i) & 0xFF;
                int code = codes[b];
                int nbits = lengths[b];

                current <<= nbits;
                current |= code;
                n += nbits;

                while (n >= 8) {
                    n -= 8;
                    out.writeByte((int) (current >> n));
                }
            }

            if (n > 0) {
                current <<= 8 - n;
                current |= 0xFF >>> n; // this should be EOS symbol
                out.writeByte((int) current);
            }
        }
    }

    /**
     * Returns the number of bytes required to Huffman encode the input string literal.
     *
     * @param data the string literal to be Huffman encoded
     * @return the number of bytes required to Huffman encode <code>data</code>
     */
    public int getEncodedLength(CharSequence data) {
        if (data instanceof AsciiString) {
            return encodedLength.get().length((AsciiString) data);
        } else {
            // slow path
            long len = 0;
            for (int i = 0; i < data.length(); i++) {
                len += lengths[data.charAt(i) & 0xFF];
            }
            return (int) ((len + 7) >> 3);
        }
    }

    private final class AsciiStringEncoder {
        private final EncodeProcessor processor = new EncodeProcessor();
        public void encode(ByteBuf out, AsciiString data) {
            try {
                processor.out = out;
                data.forEachByte(processor);
                processor.end();

            } catch (Exception e) {
                PlatformDependent.throwException(e);
            }
        }

        private final class EncodeProcessor implements ByteProcessor {
            ByteBuf out;
            private long current;
            private int n;

            @Override
            public boolean process(byte value) throws Exception {
                int b = value & 0xFF;
                int code = codes[b];
                int nbits = lengths[b];

                current <<= nbits;
                current |= code;
                n += nbits;

                while (n >= 8) {
                    n -= 8;
                    out.writeByte((int) (current >> n));
                }
                return true;
            }

            public void end() {
                try {
                    if (n > 0) {
                        current <<= 8 - n;
                        current |= 0xFF >>> n; // this should be EOS symbol
                        out.writeByte((int) current);
                    }
                } finally {
                    out = null;
                    current = 0;
                    n = 0;
                }
            }
        }
    }

    private final class AsciiStringEncodedLength  {
        private final EncodedLengthProcessor processor = new EncodedLengthProcessor();

        int length(AsciiString data) {
            try {
                data.forEachByte(processor);
                return processor.length();
            } catch (Exception e) {
                PlatformDependent.throwException(e);
                return -1;
            } finally {
                processor.len = 0;
            }
        }

        private final class EncodedLengthProcessor implements ByteProcessor {
            long len;

            @Override
            public boolean process(byte value) throws Exception {
                len += lengths[value & 0xFF];
                return true;
            }

            int length() {
                return (int) ((len + 7) >> 3);
            }
        }
    }
}
