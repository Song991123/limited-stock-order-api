package dev.mentoring.orders.config;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 헤더 검사 전에 큰 본문 전송을 허용하지 않도록 Tomcat의 100-continue 응답 시점 설정. */
@Configuration(proxyBeanMethods = false)
public class RequestBodyConfiguration {
    /** 실제 본문을 읽을 때 100 Continue 반환. 길이 초과는 필터에서 먼저 거절 가능. */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> continueOnlyWhenBodyIsRead() {
        return factory -> factory.addProtocolHandlerCustomizers(handler -> {
            if (handler instanceof AbstractHttp11Protocol<?> http) {
                http.setContinueResponseTiming("onRead");
            }
        });
    }
}
