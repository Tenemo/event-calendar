package app.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

@Named
@ApplicationScoped
public class AssetRevision {
    private final String value = Long.toString(System.currentTimeMillis(), Character.MAX_RADIX);

    public String getValue() {
        return value;
    }
}
