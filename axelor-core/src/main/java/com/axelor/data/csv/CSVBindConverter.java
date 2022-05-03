/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2022 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.data.csv;

import com.axelor.common.StringUtils;
import com.axelor.db.mapper.JsonProperty;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.converters.reflection.ReflectionProvider;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import java.util.Optional;

public class CSVBindConverter implements Converter {

  private final Converter defaultConverter;
  private final ReflectionProvider reflectionProvider;

  public CSVBindConverter(Converter defaultConverter, ReflectionProvider reflectionProvider) {
    this.defaultConverter = defaultConverter;
    this.reflectionProvider = reflectionProvider;
  }

  @Override
  public boolean canConvert(@SuppressWarnings("rawtypes") Class type) {
    return CSVBind.class.isAssignableFrom(type);
  }

  @Override
  public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
    final String field = reader.getAttribute("to");

    return Optional.ofNullable(field).orElse("").startsWith(JsonProperty.KEY_JSON_PREFIX)
        ? newInstanceJson(context, reader.getAttribute("json-model"))
        : newInstance(context);
  }

  private Object newInstance(UnmarshallingContext context) {
    return newInstance(CSVBind.class, context);
  }

  private Object newInstanceJson(UnmarshallingContext context, String jsonModel) {
    final Object result = newInstance(CSVBindJson.class, context);

    if (StringUtils.notBlank(jsonModel)) {
      reflectionProvider.writeField(result, "jsonModel", jsonModel, CSVBindJson.class);
    }

    return result;
  }

  private Object newInstance(Class<?> resultType, UnmarshallingContext context) {
    final Object result = reflectionProvider.newInstance(resultType);
    return context.convertAnother(result, resultType, defaultConverter);
  }
}
