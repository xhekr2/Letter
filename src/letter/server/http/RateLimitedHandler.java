package letter.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import letter.domain.LetterException;

import java.io.IOException;

/** 데코레이터: 다른 핸들러를 감싸서 요청 횟수 제한 기능만 덧붙인다. 감싸인 핸들러는 이 기능을 모른다. */
public class RateLimitedHandler implements HttpHandler {
    private final HttpHandler delegate;
    private final RateLimiter limiter;

    public RateLimitedHandler(HttpHandler delegate, RateLimiter limiter) {
        this.delegate = delegate;
        this.limiter = limiter;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String client = ex.getRemoteAddress().getAddress().getHostAddress();
        if (!limiter.tryAcquire(client)) {
            HttpResponses.sendError(ex, HttpResponses.statusOf(LetterException.Kind.RATE_LIMITED),
                    "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
            return;
        }
        delegate.handle(ex);
    }
}
