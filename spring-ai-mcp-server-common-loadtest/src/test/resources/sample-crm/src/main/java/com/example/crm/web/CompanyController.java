package com.example.crm.web;

import com.example.crm.domain.Company;
import com.example.crm.support.AbstractCrudController;
import com.example.crm.support.ApiController;
import org.springframework.web.bind.annotation.RequestMapping;

@ApiController
@RequestMapping("/api/companies")
public class CompanyController extends AbstractCrudController<Company, Long> {
}
