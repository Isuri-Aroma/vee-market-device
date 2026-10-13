package lk.veemarket.fake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SpringBootApplication
public class FakeServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FakeServerApplication.class, args);
    }

    /** Prints the addresses the ESP32 can use, so you don't have to hunt for your IP. */
    @EventListener(ApplicationReadyEvent.class)
    public void printAddresses(ApplicationReadyEvent event) {
        String port = event.getApplicationContext().getEnvironment().getProperty("local.server.port", "8080");
        System.out.println();
        System.out.println("==========================================================");
        System.out.println(" Vee Market FAKE server is running");
        System.out.println(" Dashboard on this Mac : http://localhost:" + port + "/");
        for (String ip : localIPv4()) {
            System.out.println(" ESP32 / other laptop  : http://" + ip + ":" + port + "/");
        }
        System.out.println(" (Use the address on the same network as the hotspot.)");
        System.out.println("==========================================================");
        System.out.println();
    }

    static List<String> localIPv4() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                ni.getInetAddresses().asIterator().forEachRemaining(a -> {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                });
            }
        } catch (Exception ignored) { }
        return out;
    }
}
