package ir.karname.pricefeed;

import ir.karname.pricefeed.PriceFeedService.RunResult;
import ir.karname.pricefeed.PriceFeedService.SourceRequest;
import ir.karname.pricefeed.PriceFeedService.SourceView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Automatic price sources. Restricted to ADMIN by SecurityConfig (/api/v1/admin/**). */
@RestController
@RequestMapping("/api/v1/admin/price-sources")
public class PriceFeedController {

    private final PriceFeedService feeds;

    public PriceFeedController(PriceFeedService feeds) {
        this.feeds = feeds;
    }

    @GetMapping
    public List<SourceView> list() {
        return feeds.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SourceView create(@RequestBody SourceRequest request) {
        return feeds.create(request);
    }

    @PutMapping("/{id}")
    public SourceView update(@PathVariable long id, @RequestBody SourceRequest request) {
        return feeds.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        feeds.delete(id);
    }

    /** Dry run of a configuration from the form; {@code id} lends stored header values. */
    @PostMapping("/test")
    public RunResult test(@RequestParam(required = false) Long id, @RequestBody SourceRequest request) {
        return feeds.test(id, request);
    }

    @PostMapping("/{id}/run")
    public RunResult run(@PathVariable long id) {
        return feeds.run(id);
    }

    @PostMapping("/run-all")
    public List<RunResult> runAll() {
        return feeds.runAll();
    }
}
