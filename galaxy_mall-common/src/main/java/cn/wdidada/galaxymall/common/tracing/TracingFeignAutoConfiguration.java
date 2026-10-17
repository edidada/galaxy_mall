package cn.wdidada.galaxymall.common.tracing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 链路透传自动配置：注册 {@link TraceFeignInterceptor}，向下游 Feign 调用注入 {@code X-Trace-Id}。
 *
 * <p><b>为什么必须单独成一个类</b>：本项目里 user / product / stock 等服务并不使用 Feign，
 * 它们的 classpath 上没有 {@code feign-core}。若把返回 type 为 {@code feign.RequestInterceptor}
 * 的 {@code @Bean} 方法放在共用的 {@link TracingAutoConfiguration} 中，Spring 在判断 Bean 类型时
 * 会反射 introspect 该配置类的方法签名，从而触发 {@code ClassNotFoundException: feign.RequestInterceptor}，
 * 直接导致服务启动失败（CrashLoopBackOff）。
 *
 * <p>拆成独立的配置类并加上类级别 {@code @ConditionalOnClass} 后：Boot 对
 * {@code AutoConfiguration.imports} 中登记的类仅用 ASM 读取注解元数据来评估条件，
 * 条件不满足时不会加载本类、也就不会解析其中的 {@code feign.*} 类型，
 * 因此无 Feign 的服务可以安全跳过。
 *
 * <p>{@code @ConditionalOnClass} 这里用 {@code name} 字符串形式，避免在本类的常量池里
 * 硬引用 ByteBuddy/ASM 之外的类型。
 */
@Configuration
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class TracingFeignAutoConfiguration {

    /**
     * 返回类型用全限定名书写，避免 import 造成额外符号引用。
     */
    @Bean
    public feign.RequestInterceptor traceFeignInterceptor() {
        return new TraceFeignInterceptor();
    }
}
