package com.example.crm.web;

import com.example.crm.domain.Contact;
import com.example.crm.support.AbstractCrudController;
import com.example.crm.support.ApiController;
import org.springframework.web.bind.annotation.RequestMapping;

@ApiController
@RequestMapping("/api/contacts")
public class ContactController extends AbstractCrudController<Contact, Long> {
}
