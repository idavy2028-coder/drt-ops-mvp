package com.idavy.drtops.jt.protocol.jt1078;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VideoAttributesCodecTest {
 @Test void decodesIndependentWireSample() {
  var b=Unpooled.wrappedBuffer(java.util.HexFormat.of().parseHex("06010001014001620404"));
  try { var a=VideoAttributesCodec.decode(b); assertEquals(320,a.audioFrameLength()); assertEquals(98,a.videoEncoding()); assertEquals(4,a.maxVideoChannels()); } finally { b.release(); }
 }
 @Test void rejectsTruncationAndTrailingBytes() {
  for(int n : new int[]{0,9,11}) { var b=Unpooled.buffer().writeZero(n); try {assertThrows(IllegalArgumentException.class,()->VideoAttributesCodec.decode(b));}finally{b.release();} }
 }
 @Test void rejectsInvalidEnums() {
  var b=Unpooled.wrappedBuffer(java.util.HexFormat.of().parseHex("06010401014001620404"));
  try { assertThrows(IllegalArgumentException.class,()->VideoAttributesCodec.decode(b)); } finally {b.release();}
 }
}
