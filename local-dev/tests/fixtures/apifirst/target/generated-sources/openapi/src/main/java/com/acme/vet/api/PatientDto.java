package com.acme.vet.api;

public class PatientDto {
  private String name;
  private String phone;
  private @Nullable Integer id;

  public String getName() { return name; }
  public String getPhone() { return phone; }
  public Integer getId() { return id; }
}
