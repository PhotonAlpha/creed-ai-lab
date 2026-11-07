package com.creed.parent.service;

import com.github.difflib.text.DiffRow;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.jxls.common.AreaListener;
import org.jxls.common.CellRef;
import org.jxls.common.Context;
import org.jxls.transform.Transformer;
import org.jxls.transform.poi.PoiTransformer;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 9/13/24
 */
@Slf4j
public class ColorfulCellListener implements AreaListener {
    private final Transformer transformer;

    public ColorfulCellListener(Transformer transformer) {
        this.transformer = transformer;
    }

    @Override
    public void beforeApplyAtCell(CellRef cellRef, Context context) {
        log.trace("beforeApplyAtCell");
    }

    @Override
    public void afterApplyAtCell(CellRef cellRef, Context context) {
        log.trace("afterApplyAtCell");
        ((PoiTransformer) transformer).getWorkbook().getSheet(cellRef.getSheetName()).getRow(cellRef.getRow()).getCell(cellRef.getCol());
        Workbook workbook = ((PoiTransformer) transformer).getWorkbook();
        String key = Optional.ofNullable(workbook)
                .map(wb -> wb.getSheet(cellRef.getSheetName()))
                .map(sheet -> sheet.getRow(cellRef.getRow()))
                .map(row -> row.getCell(cellRef.getCol()))
                .map(Cell::getStringCellValue).orElse(StringUtils.EMPTY);
        Map<String, String> dataStatus;
        if (StringUtils.equals(cellRef.getSheetName(), "Matching Data Sheet")) {
            dataStatus = (Map<String, String>)context.getVar("matchingDataStatus");
        } else {
            dataStatus = (Map<String, String>)context.getVar("nonMatchingDataStatus");
        }
        String statusStr = dataStatus.getOrDefault(key, StringUtils.EMPTY);
        String[] statusArray = StringUtils.split(statusStr, ":");
        AtomicInteger index = new AtomicInteger(2);
        Row tarRow = Optional.ofNullable(workbook)
                .map(wb -> wb.getSheet(cellRef.getSheetName()))
                .map(sheet -> sheet.getRow(cellRef.getRow())).orElse(null);
        if (Objects.nonNull(tarRow)) {
            Stream.of(statusArray)
                .forEach(status -> {
                    if (StringUtils.equals(status, DiffRow.Tag.DELETE.toString())) {
                        applyColor(workbook, tarRow, cellRef, index, IndexedColors.RED);
                    } else if (StringUtils.equals(status, DiffRow.Tag.CHANGE.toString())) {
                        applyColor(workbook, tarRow, cellRef, index, IndexedColors.BLUE);
                    } else if (StringUtils.equals(status, DiffRow.Tag.INSERT.toString())) {
                        applyColor(workbook, tarRow, cellRef, index, IndexedColors.GREEN);
                    } else {
                        // for UNCHANGE case, just increase the no.
                        index.getAndIncrement();
                    }
                });
        }
    }

    private void applyColor(Workbook workbook, Row tarRow, CellRef cellRef, AtomicInteger index, IndexedColors indexedColors) {
        Font fontDelete = workbook.createFont();
        fontDelete.setBold(true);
        fontDelete.setColor(indexedColors.getIndex());
        CellStyle style = workbook.createCellStyle();
        style.setFont(fontDelete);
        Cell colorCell = tarRow.getCell(cellRef.getCol() + index.getAndIncrement());
        if (Objects.nonNull(colorCell)) {
            colorCell.setCellStyle(style);
        }
    }

    @Override
    public void beforeTransformCell(CellRef cellRef, CellRef cellRef1, Context context) {
        log.trace("beforeTransformCell");
    }

    @Override
    public void afterTransformCell(CellRef cellRef, CellRef cellRef1, Context context) {
        log.trace("cellRef:{} cellRef1:{}", cellRef, cellRef1);
        Workbook workbook = ((PoiTransformer) transformer).getWorkbook();
        Sheet sheet = workbook.getSheet(cellRef.getSheetName());
        Row row = sheet.getRow(cellRef.getRow());
        Cell cell = row.getCell(cellRef.getCol());
        String string = Optional.ofNullable(cell).map(Cell::getStringCellValue).orElse(StringUtils.EMPTY);
        log.trace("afterTransformCell:{}", string);
        if (StringUtils.isNotBlank(string) && cellRef.getCol() == 1) {
            Row tarRow = sheet.getRow(cellRef1.getRow());
            if (Objects.nonNull(tarRow)) {
                Cell tarCell = tarRow.getCell(cellRef1.getCol());
                if (Objects.nonNull(tarCell)) {
                    String statusVal = Optional.ofNullable(tarCell).map(Cell::getStringCellValue).orElse(StringUtils.EMPTY);
                    String[] statusArray = StringUtils.split(statusVal, ":");
                    AtomicInteger index = new AtomicInteger(1);
                    String description = Stream.of(statusArray)
                            .map(status -> {
                                if (StringUtils.equals(status, "EQUAL")) {
                                    return "Source Unchanged - Target " + index.getAndIncrement();
                                } else {
                                    return "Source Modified - Status : %s - Target %s".formatted(status, index.getAndIncrement());
                                }
                            }).collect(Collectors.joining(" | "));
                    tarCell.setCellValue(description);
                }
            }
        }
    }
}
