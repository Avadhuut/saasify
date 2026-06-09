package com.saasify.user.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC Configuration class to register dynamic request filters/interceptors.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private TenantInterceptor tenantInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Intercept user-service APIs to ensure tenant datasource mapping is applied
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/**");
    }
}
