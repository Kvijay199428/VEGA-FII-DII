import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;

public class TestDate {
    public static void main(String[] args) {
        long ts = 1779993000000L;
        LocalDate ist = Instant.ofEpochMilli(ts).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
        LocalDate utc = Instant.ofEpochMilli(ts).atZone(ZoneId.of("UTC")).toLocalDate();
        System.out.println("IST: " + ist);
        System.out.println("UTC: " + utc);
    }
}
