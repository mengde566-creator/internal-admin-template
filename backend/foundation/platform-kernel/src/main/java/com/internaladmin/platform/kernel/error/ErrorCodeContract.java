package com.internaladmin.platform.kernel.error;

/**
 * The smallest error-code contract shared by the HTTP boundary and module-owned
 * error vocabularies.  Modules own their enum values; the platform only needs
 * a stable code and a user-safe default message.
 */
public interface ErrorCodeContract {
    String getCode();

    String getMessage();
}
