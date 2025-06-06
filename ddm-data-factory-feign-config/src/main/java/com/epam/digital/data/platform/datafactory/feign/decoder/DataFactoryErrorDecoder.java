/*
 * Copyright 2021 EPAM Systems.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.digital.data.platform.datafactory.feign.decoder;

import com.epam.digital.data.platform.datafactory.feign.enums.DataFactoryError;
import com.epam.digital.data.platform.starter.errorhandling.dto.ErrorDetailDto;
import com.epam.digital.data.platform.starter.errorhandling.dto.ErrorsListDto;
import com.epam.digital.data.platform.starter.errorhandling.dto.SystemErrorDto;
import com.epam.digital.data.platform.starter.errorhandling.dto.ValidationErrorDto;
import com.epam.digital.data.platform.starter.errorhandling.exception.ConstraintViolationException;
import com.epam.digital.data.platform.starter.errorhandling.exception.ForbiddenOperationException;
import com.epam.digital.data.platform.starter.errorhandling.exception.SystemException;
import com.epam.digital.data.platform.starter.errorhandling.exception.UnauthorizedException;
import com.epam.digital.data.platform.starter.errorhandling.exception.ValidationException;
import com.epam.digital.data.platform.starter.localization.MessageResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.Objects;

/**
 * The class represents an implementation of {@link ErrorDecoder} error decoder that raises
 * corresponding exception based on status.
 */
@RequiredArgsConstructor
public class DataFactoryErrorDecoder implements ErrorDecoder {

  private final ObjectMapper objectMapper;
  private final MessageResolver messageResolver;
  private final ErrorDecoder errorDecoderChain;

  @Override
  public Exception decode(String methodKey, Response response) {
    if (Objects.isNull(response) || Objects.isNull(response.body())) {
      return errorDecoderChain.decode(methodKey, response);
    }
    if (response.status() == HttpStatus.UNPROCESSABLE_ENTITY.value()
        || response.status() == HttpStatus.NOT_FOUND.value()) {
      return validationException(response);
    }
    if (response.status() == HttpStatus.SERVICE_UNAVAILABLE.value()){
      return serviceUnavailable(response);
    }
    if (response.status() == HttpStatus.FORBIDDEN.value()) {
      return forbiddenException(response);
    }
    if (response.status() == HttpStatus.CONFLICT.value()) {
      return constraintViolationException(response);
    }
    if (response.status() == HttpStatus.UNAUTHORIZED.value()) {
      return unauthorizedException(response);
    } else {
      return systemException(response);
    }
  }

  @SneakyThrows
  private SystemException systemException(Response response) {
    return new SystemException(convertResponseToSystemErrorDto(response));
  }

  @SneakyThrows
  private ForbiddenOperationException forbiddenException(Response response) {
    var systemErrorDto = objectMapper
            .readValue(response.body().asInputStream(), SystemErrorDto.class);

    var dataFactoryError = DataFactoryError.fromNameOrDefaultRuntimeError(systemErrorDto.getCode());
    var localizedMessage = messageResolver.getMessage(dataFactoryError.getTitleKey());

    systemErrorDto.setLocalizedMessage(localizedMessage);
    return new ForbiddenOperationException(systemErrorDto);
  }

  @SneakyThrows
  private ConstraintViolationException constraintViolationException(Response response) {
    var systemErrorDto = objectMapper
        .readValue(response.body().asInputStream(), SystemErrorDto.class);

    var dataFactoryError = DataFactoryError.fromNameOrDefaultRuntimeError(systemErrorDto.getCode());
    var localizedMessage = messageResolver.getMessage(dataFactoryError.getTitleKey());

    systemErrorDto.setLocalizedMessage(localizedMessage);
    return new ConstraintViolationException(systemErrorDto);
  }

  private SystemException serviceUnavailable(Response response) {
    SystemErrorDto errorDto = new SystemErrorDto();
    errorDto.setCode(HttpStatus.resolve(response.status()).name());

    var dataFactoryError = DataFactoryError.fromNameOrDefaultRuntimeError(errorDto.getCode());
    var localizedMessage = messageResolver.getMessage(dataFactoryError.getTitleKey());

    errorDto.setLocalizedMessage(localizedMessage);
    return new SystemException(errorDto);
  }

  @SneakyThrows
  private ValidationException validationException(Response response) {
    var validationErrorDto = objectMapper
        .readValue(response.body().asInputStream(), ValidationErrorDto.class);

    if (Objects.nonNull(validationErrorDto.getDetails())) {
      var localizedMessage = messageResolver
          .getMessage(DataFactoryError.VALIDATION_ERROR.getTitleKey());
      validationErrorDto.getDetails().getErrors()
          .forEach(errorDetailDto -> errorDetailDto.setMessage(localizedMessage));
    } else if (HttpStatus.NOT_FOUND.value() == response.status()) {
      var localizedMessage = messageResolver.getMessage(DataFactoryError.NOT_FOUND.getTitleKey());
      validationErrorDto.setDetails(new ErrorsListDto(Collections.singletonList(
          new ErrorDetailDto(localizedMessage, null, null))));
    }

    return new ValidationException(validationErrorDto);
  }

  @SneakyThrows
  private UnauthorizedException unauthorizedException(Response response) {
    return new UnauthorizedException(convertResponseToSystemErrorDto(response));
  }

  private SystemErrorDto convertResponseToSystemErrorDto(Response response) throws IOException {
    var bodyBytes = response.body().asInputStream().readAllBytes();
    try {
      SystemErrorDto systemErrorDto = objectMapper.readValue(bodyBytes, SystemErrorDto.class);
      var dataFactoryError = DataFactoryError.fromNameOrDefaultRuntimeError(systemErrorDto.getCode());
      var localizedMessage = messageResolver.getMessage(dataFactoryError.getTitleKey());
      systemErrorDto.setLocalizedMessage(localizedMessage);
      return systemErrorDto;
    } catch (IOException ex) {
      return convertResponseWithStringBody(bodyBytes, HttpStatus.resolve(response.status()).name());
    }
  }

  private SystemErrorDto convertResponseWithStringBody(byte[] bodyBytes, String statusName) {
    return SystemErrorDto.builder()
        .code(statusName)
        .localizedMessage(new String(bodyBytes, StandardCharsets.UTF_8))
        .build();
  }
}