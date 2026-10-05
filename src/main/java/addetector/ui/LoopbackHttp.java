package addetector.ui;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/** 이 PC에서만 접속되는(127.0.0.1) HTTP 서버를 연다. */
public final class LoopbackHttp {
    private LoopbackHttp() {}

    private static final int ATTEMPTS = 3;
    private static final long RETRY_PAUSE_MS = 300;

    /**
     * @param port 원하는 포트(0이면 빈 포트). 열지 못하면 몇 번 다시 해 보고, 그래도 안 되면 빈 포트를 고른다.
     */
    public static HttpServer create(int port) throws IOException {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        if (port > 0) {
            for (int i = 0; i < ATTEMPTS; i++) {
                try {
                    return HttpServer.create(new InetSocketAddress(loopback, port), 0);
                } catch (IOException e) {
                    // 방금 닫힌 포트는 잠깐 뒤에 열릴 수 있다.
                    try {
                        Thread.sleep(RETRY_PAUSE_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return HttpServer.create(new InetSocketAddress(loopback, 0), 0);
    }
}
