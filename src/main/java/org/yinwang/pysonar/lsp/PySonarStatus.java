package org.yinwang.pysonar.lsp;

public final class PySonarStatus {
    private String state;
    private String message;

    public PySonarStatus() {
    }

    public PySonarStatus(String state, String message) {
        this.state = state;
        this.message = message;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
