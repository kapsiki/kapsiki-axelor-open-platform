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
package com.axelor.meta.schema.views;

import com.axelor.common.StringUtils;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;

@XmlType
@JsonTypeName("calendar")
public class CalendarView extends AbstractView implements ContainerView {

  @XmlAttribute private String mode;

  @XmlAttribute private String colorBy;

  @XmlAttribute private String onChange;

  @XmlAttribute private String eventStart;

  @XmlAttribute private String eventStop;

  @XmlAttribute private Integer eventLength;

  @XmlAttribute private Integer dayLength;

  @XmlElement(name = "field", type = Field.class)
  private List<AbstractWidget> items;

  @Override
  public List<AbstractWidget> getItems() {
    return items;
  }

  @Override
  public Set<String> getExtraNames() {
    return Stream.of(getEventStart(), getEventStop(), getColorBy())
        .filter(StringUtils::notBlank)
        .collect(Collectors.toSet());
  }

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public String getColorBy() {
    return colorBy;
  }

  public void setColorBy(String colorBy) {
    this.colorBy = colorBy;
  }

  public String getOnChange() {
    return onChange;
  }

  public void setOnChange(String onChange) {
    this.onChange = onChange;
  }

  public String getEventStart() {
    return eventStart;
  }

  public void setEventStart(String eventStart) {
    this.eventStart = eventStart;
  }

  public String getEventStop() {
    return eventStop;
  }

  public void setEventStop(String eventStop) {
    this.eventStop = eventStop;
  }

  public Integer getEventLength() {
    return eventLength;
  }

  public void setEventLength(Integer eventLength) {
    this.eventLength = eventLength;
  }

  public Integer getDayLength() {
    return dayLength;
  }

  public void setDayLength(Integer dayLength) {
    this.dayLength = dayLength;
  }

  public void setItems(List<AbstractWidget> items) {
    this.items = items;
  }
}
