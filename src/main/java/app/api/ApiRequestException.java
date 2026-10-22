package app.api;

final class ApiRequestException extends RuntimeException {
    private final int status;
    private final String title;

    ApiRequestException(int status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    int status() {
        return status;
    }

    String title() {
        return title;
    }
}
