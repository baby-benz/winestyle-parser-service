package com.wine.to.up.winestyle.parser.service.service;

import com.wine.to.up.winestyle.parser.service.controller.exception.ServiceIsBusyException;

public interface ParserService {
    void startParsingJob(String alcoholType) throws ServiceIsBusyException;
}
