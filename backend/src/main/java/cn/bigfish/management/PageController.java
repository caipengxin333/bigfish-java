package cn.bigfish.management;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
public class PageController {
    @GetMapping({"/admin", "/stage"}) public String page() { return "forward:/index.html"; }
}
