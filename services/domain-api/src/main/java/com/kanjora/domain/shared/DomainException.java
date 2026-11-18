package com.kanjora.domain.shared;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String title;

    public DomainException(HttpStatus status, String errorCode, String title, String detail) {
        super(detail);
        this.status = status;
        this.errorCode = errorCode;
        this.title = title;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getTitle() {
        return title;
    }

    public static DomainException notFound(String errorCode, String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, errorCode, "Recurso no encontrado", detail);
    }

    public static DomainException conflict(String errorCode, String detail) {
        return new DomainException(HttpStatus.CONFLICT, errorCode, "Conflicto de estado o regla de negocio", detail);
    }

    public static DomainException unprocessable(String errorCode, String detail) {
        return new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, "Datos inválidos", detail);
    }

    public static DomainException forbidden(String errorCode, String detail) {
        return new DomainException(HttpStatus.FORBIDDEN, errorCode, "Acceso denegado", detail);
    }
}

