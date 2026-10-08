package io.github.stevdrey.dokene.customer.application;

import io.github.stevdrey.dokene.customer.domain.Customer;

/** User-facing (es-419) validation messages returned by the customer API. */
public final class CustomerValidationMessages {
    public static final String PHONE_INVALID = "El formato del teléfono es inválido para la región seleccionada.";
    public static final String PHONE_REGION_INVALID = "La región del teléfono es inválida o no está soportada.";
    public static final String DISPLAY_NAME_REQUIRED = "El nombre del cliente es obligatorio.";
    public static final String DISPLAY_NAME_TOO_LONG =
            "El nombre del cliente debe tener como máximo " + Customer.DISPLAY_NAME_MAX_LENGTH + " caracteres.";
    public static final String DISPLAY_NAME_INVALID_CHARACTERS =
            "El nombre del cliente no puede contener caracteres nulos ni Unicode inválido.";
    public static final String VERSION_REQUIRED =
            "La versión del cliente es obligatoria (encabezado If-Match o campo version).";
    public static final String VERSION_INVALID = "La versión del cliente es inválida.";
    public static final String REQUEST_INVALID = "La solicitud o sus parámetros son inválidos.";
    public static final String INPUT_INVALID = "La entrada es inválida.";
    public static final String CONSENT_REQUIRED = "El estado y el origen del consentimiento son obligatorios.";
    public static final String DO_NOT_CONTACT_REQUIRED = "El estado y el origen de no contactar son obligatorios.";
    public static final String IF_MATCH_REQUIRED = "El encabezado If-Match es obligatorio.";
    public static final String CONTACT_POLICY_VERSION_INVALID = "La versión de la política de contacto es inválida.";
    public static final String HISTORY_LIMIT_INVALID = "El límite del historial debe estar entre 1 y 100.";

    private CustomerValidationMessages() { }
}
