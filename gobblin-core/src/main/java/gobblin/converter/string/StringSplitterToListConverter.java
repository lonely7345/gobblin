/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.converter.string;

import java.util.List;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;

import gobblin.configuration.ConfigurationKeys;
import gobblin.configuration.WorkUnitState;
import gobblin.converter.Converter;
import gobblin.converter.DataConversionException;
import gobblin.converter.SchemaConversionException;
import gobblin.converter.SingleRecordIterable;
import gobblin.util.ForkOperatorUtils;


/**
 * Splits a {@link String} record into a record that is {@link List} of {@link String}, based on
 * {@link ConfigurationKeys#CONVERTER_STRING_SPLITTER_DELIMITER}.
 */
public class StringSplitterToListConverter extends Converter<String, String, String, List<String>> {

  private Splitter splitter;
  private boolean shouldTrimResults;

  @Override
  public Converter<String, String, String, List<String>> init(WorkUnitState workUnit) {
    String stringSplitterDelimiterKey =
        ForkOperatorUtils.getPropertyNameForBranch(workUnit, ConfigurationKeys.CONVERTER_STRING_SPLITTER_DELIMITER);
    Preconditions.checkArgument(workUnit.contains(stringSplitterDelimiterKey),
        "Cannot use " + this.getClass().getName() + " with out specifying "
            + ConfigurationKeys.CONVERTER_STRING_SPLITTER_DELIMITER);
    this.splitter = Splitter.on(workUnit.getProp(stringSplitterDelimiterKey));
    this.shouldTrimResults = workUnit.getPropAsBoolean(ConfigurationKeys.CONVERTER_STRING_SPLITTER_SHOULD_TRIM_RESULTS,
        ConfigurationKeys.DEFAULT_CONVERTER_STRING_SPLITTER_SHOULD_TRIM_RESULTS);
    return this;
  }

  @Override
  public String convertSchema(String inputSchema, WorkUnitState workUnit)
      throws SchemaConversionException {
    return inputSchema;
  }

  @Override
  public Iterable<List<String>> convertRecord(String outputSchema, String inputRecord, WorkUnitState workUnit)
      throws DataConversionException {
    List<String> convertedRecord =
        this.shouldTrimResults ? this.splitter.omitEmptyStrings().trimResults().splitToList(inputRecord)
            : this.splitter.splitToList(inputRecord);
    return new SingleRecordIterable<>(convertedRecord);
  }
}
