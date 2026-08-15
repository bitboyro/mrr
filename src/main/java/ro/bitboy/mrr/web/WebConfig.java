package ro.bitboy.mrr.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.auth.TokenFilter;

import java.util.List;

/**
 * Lets a controller take a {@link Principal} parameter directly. TokenFilter has already
 * rejected anything without a valid token, so by the time a handler runs the attribute is
 * guaranteed present on {@code /api/**}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {

            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return Principal.class.equals(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                          NativeWebRequest request, WebDataBinderFactory binder) {
                return request.getAttribute(TokenFilter.PRINCIPAL_ATTRIBUTE,
                        NativeWebRequest.SCOPE_REQUEST);
            }
        });
    }
}
