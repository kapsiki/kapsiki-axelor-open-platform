import React, { useCallback, useMemo } from "react";
import dayjs from "dayjs";
import { Button, Box, Popper, ClickAwayListener, useTheme } from "@axelor/ui";

import { i18n } from "@/services/client/i18n";
import { SchedulerEvent } from "@axelor/ui/scheduler";
import { DataRecord } from "@/services/client/data.types";

const { get: _t } = i18n;

interface CalendarEventPopperProps {
  data: SchedulerEvent | null;
  anchorEl: HTMLElement | null;
  onClose: () => void;
  onEdit?: (event: SchedulerEvent) => void;
  onView?: (event: SchedulerEvent) => void;
  onDelete?: (event: SchedulerEvent) => void;
  eventStart: string;
  eventStop?: string;
  isDateCalendar: boolean;
}

export default function CalendarEventPopper({
  data,
  anchorEl,
  onClose,
  onEdit,
  onView,
  onDelete,
  eventStart,
  eventStop,
  isDateCalendar,
}: CalendarEventPopperProps) {
  const rtl = useTheme().dir === "rtl";
  const dateFormat = isDateCalendar ? "LL" : "LLL";
  const record = (data as Record<string, any>)?.record as DataRecord;

  const formatDate = useCallback(
    (date: Date) => dayjs(date).format(dateFormat),
    [dateFormat]
  );

  const formattedDates = useMemo(() => {
    if (!record) return "";
    const _start = record[eventStart];
    const _end = eventStop && record[eventStop];
    const start = _start && formatDate(_start);
    const end = _end && formatDate(_end);
    if (end && start !== end) {
      return `${start} – ${end}`;
    }
    return start;
  }, [record, eventStart, eventStop, formatDate]);

  return (
    data &&
    anchorEl && (
      <Popper
        open={Boolean(anchorEl)}
        target={anchorEl}
        placement="top"
        arrow
        rounded
        shadow
      >
        <ClickAwayListener onClickAway={onClose}>
          <Box
            bgColor="body"
            {...(rtl ? { dir: "rtl" } : {})}
            d="flex"
            flexDirection="column"
          >
            <Box w={100} textAlign="center" p={2} rounded={3}>
              <Box as="p" mb={0} color="dark">
                {data.title}
              </Box>
            </Box>
            <Box p={0} w={100} textAlign="center" border>
              <Box as="p" p={2} mb={0}>
                {formattedDates}
              </Box>
            </Box>
            <Box
              d="flex"
              w={100}
              p={1}
              justifyContent="space-between"
              alignItems="center"
            >
              {onDelete && (
                <Button variant="link" as="a" onClick={() => onDelete(data)}>
                  {_t("Delete")}
                </Button>
              )}
              {onEdit && (
                <Button variant="link" as="a" onClick={() => onEdit(data)}>
                  {_t("Edit event")}
                </Button>
              )}
              {!onEdit && onView && (
                <Button variant="link" as="a" onClick={() => onView(data)}>
                  {_t("View event")}
                </Button>
              )}{" "}
            </Box>
          </Box>
        </ClickAwayListener>
      </Popper>
    )
  );
}