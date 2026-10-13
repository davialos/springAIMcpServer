package com.acme.vet;

import com.acme.vet.api.PatientsApi;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PatientController implements PatientsApi {
}
