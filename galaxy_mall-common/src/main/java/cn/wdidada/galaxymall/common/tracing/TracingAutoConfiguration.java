package cn.wdidada.galaxymall.common.tracing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一追踪自动配置：注册 servlet 过滤器 {@link TraceServletFilter}。
 *
 * <p>类级别限定为 {@code SERVLET}：网关是 WebFlux（classpath 无 servlet API），
 * Boot 对 {@code AutoConfiguration.imports} 里的类只用 ASM 读注解元数据来评估条件，
 * 结果为 false 时不会真正加载本类，因此不会触发 {@link TraceServletFilter}
 * 对 {@code jakarta.servlet} 的依赖而报错。
 *
 * <p><b>注意</b>：Feign 拦截器不能放在本类，见 {@link TracingFeignAutoConfiguration} 的说明。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TracingAutoConfiguration {

    @Bean
    public FilterRegistrationBean<TraceServletFilter> traceServletFilter() {
        FilterRegistrationBean<TraceServletFilter> registration =
                new FilterRegistrationBean<>(new TraceServletFilter());
        registration.setOrder(-100);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
