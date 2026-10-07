package com.github.huchangjun118.sandboxedruleruntimejava.web.dto;

/** REST body for rolling a rule back to a prior version. */
public class RollbackRequest {
    private int version;

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
