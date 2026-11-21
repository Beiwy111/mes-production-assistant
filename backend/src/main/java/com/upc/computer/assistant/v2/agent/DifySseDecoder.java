package com.upc.computer.assistant.v2.agent;

import java.io.IOException;
import java.io.Reader;
import java.util.function.Predicate;

/** 有界 SSE 帧解析；忽略注释和无 data 的 ping，不把内部事件原样输出。 */
public final class DifySseDecoder {
    private static final int MAX_FRAME_CHARS = 262144;
    private DifySseDecoder() { }

    public static void read(Reader reader, Predicate<String> consume) throws IOException {
        StringBuilder line = new StringBuilder();
        StringBuilder data = new StringBuilder();
        boolean afterCr = false;
        int frameChars = 0;
        int ch;
        while ((ch = reader.read()) != -1) {
            if (afterCr && ch == '\n') { afterCr = false; continue; }
            afterCr = ch == '\r';
            if (++frameChars > MAX_FRAME_CHARS) throw new IOException("SSE frame exceeds limit");
            if (ch != '\r' && ch != '\n') { line.append((char) ch); continue; }
            if (line.isEmpty()) {
                if (!data.isEmpty() && !consume.test(data.substring(0, data.length() - 1))) return;
                data.setLength(0);
                frameChars = 0;
            } else if (line.toString().startsWith("data:")) {
                String value = line.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value).append('\n');
            }
            line.setLength(0);
        }
        // EOF 不提交尚未以空行结束的半帧。
    }
}
