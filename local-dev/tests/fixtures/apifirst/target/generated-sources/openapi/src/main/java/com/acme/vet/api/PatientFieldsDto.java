package com.acme.vet.api;

import jakarta.validation.constraints.*;

public class PatientFieldsDto {
  private String name;
  private String phone;

  @NotNull @Pattern(regexp = "^[\\p{L}]+([ '-][\\p{L}]+){0,2}$") @Size(min = 1, max = 30)
  public String getName() { return name; }

  @NotNull @Pattern(regexp = "^[0-9]*$") @Size(min = 1, max = 12)
  public String getPhone() { return phone; }
}
