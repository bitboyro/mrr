package ro.bitboy.mrr.web;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Client routes of the Next static export. Spring Boot already serves files under
 * {@code classpath:/static/}; these mappings exist so a deep link like {@code /projects/x}
 * still gets {@code index.html} instead of 404.
 */
@Hidden
@Controller
public class SpaController {

    @GetMapping({
            "/",
            "/login",
            "/howto",
            "/projects",
            "/projects/{id}",
            "/projects/{id}/edit",
            "/projects/{id}/mrs/{iid}",
            "/reviews/{id}",
            "/admin",
            "/logs",
            "/configure"
    })
    public String spa() {
        return "forward:/index.html";
    }
}
