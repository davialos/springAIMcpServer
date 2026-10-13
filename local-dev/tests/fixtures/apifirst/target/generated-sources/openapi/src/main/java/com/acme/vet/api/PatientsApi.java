package com.acme.vet.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Shape of OpenAPI Generator (spring, interfaceOnly) output: mappings on interface default methods, paths as constants. */
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen")
public interface PatientsApi {
    String PATH_ADD_PATIENT = "/patients";
    String PATH_GET_PATIENT = "/patients/{patientId}";

    @RequestMapping(
        method = RequestMethod.POST,
        value = PatientsApi.PATH_ADD_PATIENT,
        produces = { "application/json" },
        consumes = { "application/json" }
    )
    default ResponseEntity<PatientDto> addPatient(@Valid @RequestBody PatientFieldsDto patientFieldsDto) {
        return null;
    }

    @RequestMapping(method = RequestMethod.GET, value = PatientsApi.PATH_GET_PATIENT, produces = { "application/json" })
    default ResponseEntity<PatientDto> getPatient(@PathVariable("patientId") Integer patientId) {
        return null;
    }
}
