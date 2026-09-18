import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.util.concurrent.atomic.AtomicLong;

public class Probe {
    public static void main(String[] args) {
        PrometheusMeterRegistry reg = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        reg.gauge("cabinet.devices.total", new AtomicLong(3));
        reg.gauge("cabinet.devices.online", new AtomicLong(2));
        reg.gauge("cabinet.devices.count", new AtomicLong(4));
        reg.counter("cabinet.door.open", "result", "success").increment();
        System.out.println("---- scrape ----");
        System.out.println(reg.scrape());
    }
}
