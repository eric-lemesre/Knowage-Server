/*
 * Knowage, Open Source Business Intelligence suite
 * Copyright (C) 2021 Engineering Ingegneria Informatica S.p.A.
 * Knowage is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * Knowage is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.eng.knowage.engine.api.excel.export.oldcockpit;

import com.google.gson.Gson;
import it.eng.knowage.commons.multitenant.OrganizationImageManager;
import it.eng.knowage.engine.api.excel.export.ExporterClient;
import it.eng.knowage.engine.api.excel.export.IWidgetExporter;
import it.eng.knowage.engine.api.excel.export.oldcockpit.exporters.WidgetExporterFactory;
import it.eng.spago.error.EMFAbstractError;
import it.eng.spagobi.analiticalmodel.document.bo.BIObject;
import it.eng.spagobi.analiticalmodel.document.bo.ObjTemplate;
import it.eng.spagobi.analiticalmodel.document.dao.IBIObjectDAO;
import it.eng.spagobi.behaviouralmodel.analyticaldriver.bo.BIObjectParameter;
import it.eng.spagobi.commons.SingletonConfig;
import it.eng.spagobi.commons.bo.Config;
import it.eng.spagobi.commons.dao.DAOFactory;
import it.eng.spagobi.commons.dao.IConfigDAO;
import it.eng.spagobi.tenant.TenantManager;
import it.eng.spagobi.tools.dataset.bo.IDataSet;
import it.eng.spagobi.utilities.exceptions.SpagoBIRuntimeException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Francesco Lucchi (francesco.lucchi@eng.it)
 * @author Marco Balestri (marco.balestri@eng.it)
 */

public class ExcelExporter extends AbstractFormatExporter {

    private static final Logger LOGGER = LogManager.getLogger(ExcelExporter.class);
    private static final String[] WIDGETS_TO_IGNORE = {"image", "text", "selector", "selection", "html"};
    private static final String SCRIPT_NAME = "cockpit-export-xls.js";
    private static final String CONFIG_NAME_FOR_EXPORT_SCRIPT_PATH = "internal.nodejs.chromium.export.path";
    private static final String CONFIG_NAME_FOR_DRIVERS_SHEET_EXPORT = "COCKPIT.EXPORT.SHOW_FILTER";
    private static final int SHEET_NAME_MAX_LEN = 31;
    private static final int EXCEL_CELL_MAX_LEN = 32767;

    public static final String UNIQUE_ALIAS_PLACEHOLDER = "_$_";

    private final boolean isSingleWidgetExport;
    private int uniqueId = 0;
    private String requestURL = "";

    private static final String INT_CELL_DEFAULT_FORMAT = "0";
    private static final String FLOAT_CELL_DEFAULT_FORMAT = "#,##0.00";

    private String imageB64 = "";
    private String documentName = "";
    private final Map<String, Object> properties;

    // used only for scheduled export
    public ExcelExporter(String userUniqueIdentifier, Map<String, String[]> parameterMap, String requestURL) {
        super(userUniqueIdentifier, new JSONObject());
        this.isSingleWidgetExport = false;
        this.requestURL = requestURL;
        this.properties = new HashMap<>();
    }

    public ExcelExporter(String userUniqueIdentifier, JSONObject body) {
        super(userUniqueIdentifier, body);
        this.isSingleWidgetExport = body.optBoolean("exportWidget");
        this.properties = new HashMap<>();
    }

    public void setProperty(String propertyName, Object propertyValue) {
        this.properties.put(propertyName, propertyValue);
    }

    public Object getProperty(String propertyName) {
        return this.properties.get(propertyName);
    }

    public String getMimeType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    public byte[] getBinaryData(Integer documentId, String documentLabel, String documentName, String templateString, String options)
            throws JSONException {
        // set document name for exporting
        this.documentName = documentName;

        if (templateString == null) {
            ObjTemplate template = null;
            String message = "Unable to get template for document with id [" + documentId + "] and label ["
                    + documentLabel + "]";
            try {
                if (documentId != null && documentId.intValue() != 0) {
                    template = DAOFactory.getObjTemplateDAO().getBIObjectActiveTemplate(documentId);
                } else if (documentLabel != null && !documentLabel.isEmpty()) {
                    template = DAOFactory.getObjTemplateDAO().getBIObjectActiveTemplateByLabel(documentLabel);
                }

                if (template == null) {
                    throw new SpagoBIRuntimeException(message);
                }

                templateString = new String(template.getContent());
            } catch (EMFAbstractError e) {
                throw new SpagoBIRuntimeException(message);
            }
        }

        int windowSize = Integer.parseInt(
                SingletonConfig.getInstance().getConfigValue("KNOWAGE.DASHBOARD.EXPORT.EXCEL.STREAMING_WINDOW_SIZE"));
        try (Workbook wb = new SXSSFWorkbook(windowSize)) {

            int exportedSheets = 0;
            if (isSingleWidgetExport) {
                long widgetId = body.getLong("widget");
                String widgetType = getWidgetTypeFromCockpitTemplate(templateString, widgetId);
                JSONObject optionsObj = new JSONObject();
                if (options != null && !options.isEmpty()) {
                    optionsObj = new JSONObject(options);
                }
                IWidgetExporter widgetExporter = WidgetExporterFactory.getExporter(this, widgetType, templateString,
                        widgetId, wb, optionsObj);
                exportedSheets = widgetExporter.export();

                IConfigDAO configsDao = DAOFactory.getSbiConfigDAO();
                Optional<Config> cfg = configsDao.loadConfigParametersByLabelIfExist(CONFIG_NAME_FOR_DRIVERS_SHEET_EXPORT);

                if (cfg.isPresent() && cfg.get().isActive() && Boolean.parseBoolean(cfg.get().getValueCheck())) {

                    Map<String, Map<String, Object>> selectionsMap;
                try {
                    selectionsMap = createSelectionsMap();
                } catch (JSONException e) {
                    throw new SpagoBIRuntimeException("Unable to get selection map: ", e);
                }
                if (!selectionsMap.isEmpty()) {
                    Sheet selectionsSheet = createUniqueSafeSheetForSelections(wb, "Active Selections");
                    fillSelectionsSheetWithData(selectionsMap, wb, selectionsSheet, "Selections");
                    exportedSheets++;
                }

                    Map<String, Map<String, Object>> driversMap;
                    try {
                        driversMap = createDriversMap();
                    } catch (JSONException e) {
                        throw new SpagoBIRuntimeException("Unable to get driver map: ", e);
                    }
                    if (!driversMap.isEmpty()) {
                        Sheet driversSheet = createUniqueSafeSheetForDrivers(wb, "Filters");
                        fillDriversSheetWithData(driversMap, wb, driversSheet, "Filters");
                        exportedSheets++;
                    }
                }
            } else {
                // export whole cockpit
                JSONArray widgetsJson = getWidgetsJson(templateString);
                JSONObject optionsObj = buildOptionsForCrosstab(templateString);
                exportedSheets = exportCockpit(templateString, widgetsJson, wb, optionsObj);
            }

            if (exportedSheets == 0) {
                exportEmptyExcel(wb);
            } else {
                for (Sheet sheet : wb) {
                    if (sheet != null) {
                        // Adjusts the column width to fit the contents
                        adjustColumnWidth(sheet, this.imageB64);
                    }
                }
            }

            byte[] ret = null;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                out.flush();
                ret = out.toByteArray();
            }
            return ret;
        } catch (IOException e) {
            throw new SpagoBIRuntimeException("Unable to generate output file", e);
        } catch (Exception e) {
            throw new SpagoBIRuntimeException("Cannot export data to excel", e);
        }
    }

    private JSONObject buildOptionsForCrosstab(String templateString) {
        try {
            JSONObject template = new JSONObject(templateString);
            JSONArray sheets = template.getJSONArray("sheets");
            JSONObject toReturn = new JSONObject();
            for (int i = 0; i < sheets.length(); i++) {
                JSONObject sheet = sheets.getJSONObject(i);
                JSONArray sheetWidgets = sheet.getJSONArray("widgets");
                for (int j = 0; j < sheetWidgets.length(); j++) {
                    JSONObject widget = sheetWidgets.getJSONObject(j);
                    if (!widget.getString("type").equals("static-pivot-table")) {
                        continue;
                    }
                    long widgetId = widget.getLong("id");
                    JSONObject options = new JSONObject();
                    JSONObject widgetContentFromTemplate = widget.optJSONObject("content");
                    JSONObject widgetContentFromBody = getWidgetContentFromBody(widget);
                    try {
                        // config retrieved from static object
                        options.put("config", new JSONObject().put("type", "pivot"));

                        // sortOptions retrieved from template otherwise from request body
                        if (!ObjectUtils.isEmpty(widgetContentFromTemplate) && !widgetContentFromTemplate.isNull("sortOptions")) {
                            options.put("sortOptions", widgetContentFromTemplate.getJSONObject("sortOptions"));
                        } else if (!ObjectUtils.isEmpty(widgetContentFromBody) && !widgetContentFromBody.isNull("sortOptions")) {
                            options.put("sortOptions", widgetContentFromBody.getJSONObject("sortOptions"));
                        } else {
                            options.put("sortOptions", new JSONObject());
                        }

                        // name retrieved from template otherwise from request body
                        if (!ObjectUtils.isEmpty(widgetContentFromTemplate) && !widgetContentFromTemplate.isNull("name")) {
                            options.put("name", widgetContentFromTemplate.getString("name"));
                        } else if (!ObjectUtils.isEmpty(widgetContentFromBody) && !widgetContentFromBody.isNull("name")) {
                            options.put("name", widgetContentFromBody.getString("name"));
                        } else {
                            options.put("name", new JSONObject());
                        }

                        // crosstabDefinition retrieved from template otherwise from request body
                        if (!ObjectUtils.isEmpty(widgetContentFromTemplate) && !widgetContentFromTemplate.isNull("crosstabDefinition")) {
                            options.put("crosstabDefinition", widgetContentFromTemplate.getJSONObject("crosstabDefinition"));
                        } else if (!ObjectUtils.isEmpty(widgetContentFromBody) && !widgetContentFromBody.isNull("crosstabDefinition")) {
                            options.put("crosstabDefinition", widgetContentFromBody.getJSONObject("crosstabDefinition"));
                        } else {
                            options.put("crosstabDefinition", new JSONObject());
                        }

                        // style retrieved from template otherwise from request body
                        if (!ObjectUtils.isEmpty(widgetContentFromTemplate) && !widgetContentFromTemplate.isNull("style")) {
                            options.put("style", widgetContentFromTemplate.getJSONObject("style"));
                        } else if (!ObjectUtils.isEmpty(widgetContentFromBody) && !widgetContentFromBody.isNull("style")) {
                            options.put("style", widgetContentFromBody.getJSONObject("style"));
                        } else {
                            options.put("style", new JSONObject());
                        }

                        // variables cannot be retrieved from template so we must recover them from request body
                        options.put("variables", getCockpitVariables());

                        ExporterClient client = new ExporterClient();
                        int datasetId = widget.getJSONObject("dataset").getInt("dsId");
                        String dsLabel = getDatasetLabel(template, datasetId);
                        String selections = getCockpitSelectionsFromBody(widget).toString();
                        JSONObject configuration = template.getJSONObject("configuration");
                        Map<String, Object> parametersMap = new HashMap<>();
                        if (getRealtimeFromWidget(datasetId, configuration)) {
                            parametersMap.put("nearRealtime", true);
                        }
                        JSONObject datastore = client.getDataStore(parametersMap, dsLabel, userUniqueIdentifier,
                                selections);
                        options.put("metadata", datastore.getJSONObject("metaData"));
                        options.put("jsonData", datastore.getJSONArray("rows"));
                        toReturn.put(String.valueOf(widgetId), options);
                    } catch (Exception e) {
                        LOGGER.warn(
                                "Cannot build crosstab options for widget {}. Only raw data without formatting will be exported.",
                                widgetId, e);
                    }
                }
            }
            return toReturn;
        } catch (Exception e) {
            LOGGER.warn("Error while building crosstab options. Only raw data without formatting will be exported.", e);
            return new JSONObject();
        }
    }

    @Override
    protected JSONObject getCockpitSelectionsFromBody(JSONObject widget) {
        JSONObject cockpitSelections = new JSONObject();
        if (body == null || body.length() == 0) {
            return cockpitSelections;
        }
        try {
            if (isSingleWidgetExport) { // export single widget
                cockpitSelections = body.getJSONObject("COCKPIT_SELECTIONS");
            } else { // export whole cockpit
                JSONArray allWidgets = body.getJSONArray("widget");
                int i;
                for (i = 0; i < allWidgets.length(); i++) {
                    JSONObject curWidget = allWidgets.getJSONObject(i);
                    if (curWidget.getLong("id") == widget.getLong("id")) {
                        break;
                    }
                }
                cockpitSelections = body.getJSONArray("COCKPIT_SELECTIONS").getJSONObject(i);
            }
            forceUniqueHeaders(cockpitSelections);
        } catch (Exception e) {
            LOGGER.error("Cannot get cockpit selections", e);
            return new JSONObject();
        }
        return cockpitSelections;
    }

    private String getDatasetLabel(JSONObject template, int dsId) {
        try {
            JSONArray cockpitDatasets = template.getJSONObject("configuration").getJSONArray("datasets");
            for (int i = 0; i < cockpitDatasets.length(); i++) {
                int currDsId = cockpitDatasets.getJSONObject(i).getInt("dsId");
                if (currDsId == dsId) {
                    return cockpitDatasets.getJSONObject(i).getString("dsLabel");
                }
            }
        } catch (Exception e) {
            throw new SpagoBIRuntimeException("Cannot retrieve dataset label for dsId: " + dsId, e);
        }
        throw new SpagoBIRuntimeException("No dataset found with dsId: " + dsId);
    }

    private JSONArray getWidgetsJson(String templateString) {
        try {
            if (body != null && body.has("widget")) {
                return body.getJSONArray("widget");
            } else {
                JSONArray toReturn = new JSONArray();
                JSONObject template = new JSONObject(templateString);
                JSONArray sheets = template.getJSONArray("sheets");
                for (int i = 0; i < sheets.length(); i++) {
                    JSONObject sheet = sheets.getJSONObject(i);
                    JSONArray sheetWidgets = sheet.getJSONArray("widgets");
                    for (int j = 0; j < sheetWidgets.length(); j++) {
                        JSONObject widget = sheetWidgets.getJSONObject(j);
                        toReturn.put(widget);
                    }
                }
                return toReturn;
            }
        } catch (Exception e) {
            throw new SpagoBIRuntimeException("Cannot retrieve widgets list", e);
        }
    }


    private int exportCockpit(String templateString, JSONArray widgetsJson, Workbook wb, JSONObject optionsObj) {
        String widgetId = null;
        int exportedSheets = 0;
        for (int i = 0; i < widgetsJson.length(); i++) {
            try {
                JSONObject currWidget = widgetsJson.getJSONObject(i);
                widgetId = currWidget.getString("id");
                String widgetType = currWidget.getString("type");
                if (Arrays.asList(WIDGETS_TO_IGNORE).contains(widgetType.toLowerCase())) {
                    continue;
                }
                JSONObject currWidgetOptions = new JSONObject();
                if (optionsObj.has(widgetId)) {
                    currWidgetOptions = optionsObj.getJSONObject(widgetId);
                }
                IWidgetExporter widgetExporter = WidgetExporterFactory.getExporter(this, widgetType, templateString,
                        Long.parseLong(widgetId), wb, currWidgetOptions);
                exportedSheets += widgetExporter.export();

            } catch (Exception e) {
                LOGGER.error("Error while exporting widget {}", widgetId, e);
            }
        }

        try {
            IConfigDAO configsDao = DAOFactory.getSbiConfigDAO();
            Optional<Config> cfg = configsDao.loadConfigParametersByLabelIfExist(CONFIG_NAME_FOR_DRIVERS_SHEET_EXPORT);

            if (cfg.isPresent() && cfg.get().isActive() && Boolean.parseBoolean(cfg.get().getValueCheck())) {
                Map<String, Map<String, Object>> selectionsMap;
                try {
                    selectionsMap = createSelectionsMap();
                } catch (JSONException e) {
                    throw new SpagoBIRuntimeException("Unable to get selection map: ", e);
                }
                if (!selectionsMap.isEmpty()) {
                    Sheet selectionsSheet = createUniqueSafeSheetForSelections(wb, "Active Selections");
                    fillSelectionsSheetWithData(selectionsMap, wb, selectionsSheet, "Selections");
                    exportedSheets++;
                }


                Map<String, Map<String, Object>> driversMap = new HashMap<>();
                try {
                    driversMap = createDriversMap();
                } catch (JSONException e) {
                    throw new SpagoBIRuntimeException("Unable to get driver map: ", e);
                }
                if (!driversMap.isEmpty()) {
                    Sheet driversSheet = createUniqueSafeSheetForSelections(wb, "Filters");
                    fillDriversSheetWithData(driversMap, wb, driversSheet, "Filters");
                    exportedSheets++;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return exportedSheets;
    }

    private void exportEmptyExcel(Workbook wb) {
        if (wb.getNumberOfSheets() == 0) {
            Sheet sh = wb.createSheet();
            Row row = sh.createRow(0);
            Cell cell = row.createCell(0);
            cell.setCellValue("No data");
        }
    }

    public JSONArray getMultiDataStoreForWidget(JSONObject template, JSONObject widget) {
        Map<String, Object> map = new HashMap<>();
        JSONArray multiDataStore = new JSONArray();
        try {
            JSONObject configuration = template.getJSONObject("configuration");
            JSONArray datasetIds = widget.getJSONArray("datasetId");
            for (int i = 0; i < datasetIds.length(); i++) {
                int datasetId = datasetIds.getInt(i);
                IDataSet dataset = DAOFactory.getDataSetDAO().loadDataSetById(datasetId);
                String datasetLabel = dataset.getLabel();
                JSONObject cockpitSelections = getMultiCockpitSelectionsFromBody(widget, datasetId);
                if (isEmptyLayer(cockpitSelections)) {
                    continue;
                }

                if (getRealtimeFromWidget(datasetId, configuration)) {
                    map.put("nearRealtime", true);
                }

                JSONArray summaryRow = getSummaryRowFromWidget(widget);
                if (summaryRow != null) {
                    cockpitSelections.put("summaryRow", summaryRow);
                }

                if (isSolrDataset(dataset)) {
                    JSONObject jsOptions = new JSONObject();
                    jsOptions.put("solrFacetPivot", true);
                    cockpitSelections.put("options", jsOptions);
                }

                JSONObject dataStore = getDatastore(datasetLabel, map, cockpitSelections.toString());
                dataStore.put("widgetData", widget);
                multiDataStore.put(dataStore);
            }
        } catch (Exception e) {
            throw new SpagoBIRuntimeException("Unable to get multi datastore for map widget: ", e);
        }
        return multiDataStore;
    }

    private boolean isEmptyLayer(JSONObject cockpitSelections) {
        try {
            JSONObject aggregations = cockpitSelections.getJSONObject("aggregations");
            JSONArray measures = aggregations.getJSONArray("measures");
            JSONArray categories = aggregations.getJSONArray("categories");
            if (measures.length() > 0 || categories.length() > 0) {
                return false;
            } else {
                return true;
            }
        } catch (Exception e) {
            LOGGER.warn("Error while checking if layer is empty", e);
            return false;
        }
    }

    public JSONObject getDataStoreForWidget(JSONObject template, JSONObject widget) {
        // if pagination is disabled offset = 0, fetchSize = -1
        return getDataStoreForWidget(template, widget, 0, -1);
    }


    private JSONObject getMultiCockpitSelectionsFromBody(JSONObject widget, int datasetId) {
        JSONObject cockpitSelections = new JSONObject();
        JSONArray allSelections = new JSONArray();
        try {
            if (body == null || body.length() == 0) {
                return cockpitSelections;
            }
            if (isSingleWidgetExport) { // export single widget with multi dataset
                allSelections = body.getJSONArray("COCKPIT_SELECTIONS");
                for (int i = 0; i < allSelections.length(); i++) {
                    if (allSelections.getJSONObject(i).getInt("datasetId") == datasetId) {
                        cockpitSelections = allSelections.getJSONObject(i);
                    }
                }
            } else { // export whole cockpit
                JSONArray allWidgets = body.getJSONArray("widget");
                int i;
                for (i = 0; i < allWidgets.length(); i++) {
                    JSONObject curWidget = allWidgets.getJSONObject(i);
                    if (curWidget.getString("id").equals(widget.getString("id"))) {
                        break;
                    }
                }
                allSelections = body.getJSONArray("COCKPIT_SELECTIONS").getJSONArray(i);
                for (int j = 0; j < allSelections.length(); j++) {
                    if (allSelections.getJSONObject(j).getInt("datasetId") == datasetId) {
                        cockpitSelections = allSelections.getJSONObject(j);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Cannot get cockpit selections", e);
            return new JSONObject();
        }
        return cockpitSelections;
    }

    public void createAndFillExcelSheet(JSONObject dataStore, Workbook wb, String widgetName, String cockpitSheetName) {
        Sheet newSheet = createUniqueSafeSheet(wb, widgetName, cockpitSheetName);
        fillSheetWithData(dataStore, wb, newSheet, widgetName, 0, null);
    }

    private void fillSelectionsSheetWithData(Map<String, Map<String, Object>> selectionsMap, Workbook wb, Sheet sheet,
                                             String widgetName) {

        // CREATE BRANDED HEADER SHEET
        this.imageB64 = OrganizationImageManager.getOrganizationB64ImageWide(TenantManager.getTenant().getName());
        int startRow = 0;
        float rowHeight = 35; // in points
        int rowspan = 2;
        int startCol = 0;
        int colWidth = 25;
        int colspan = 2;
        int namespan = 10;
        int dataspan = 10;

        Row newheader;

        int headerIndex = createBrandedHeaderSheet(
                sheet,
                this.imageB64,
                startRow,
                rowHeight,
                rowspan,
                startCol,
                colWidth,
                colspan,
                namespan,
                dataspan,
                this.documentName,
                sheet.getSheetName());

        newheader = sheet.createRow((short) headerIndex + 1); // first row

        Cell cell = newheader.createCell(0);
        cell.setCellValue("Dataset");
        CellStyle headerCellStyle = buildCellStyle(sheet, true, HorizontalAlignment.LEFT, VerticalAlignment.CENTER, (short) 11);
        cell.setCellStyle(headerCellStyle);

        Cell cell2 = newheader.createCell(1);
        cell2.setCellValue("Field");
        cell2.setCellStyle(headerCellStyle);

        Cell cell3 = newheader.createCell(2);
        cell3.setCellValue("Values");
        cell3.setCellStyle(headerCellStyle);

        int j = headerIndex + 2;
        for (String key : selectionsMap.keySet()) {

            for (String selectionskey : selectionsMap.get(key).keySet()) {

                Row row = sheet.createRow(j++);

                Cell cellData0 = row.createCell(0);
                if (key != null)
                    cellData0.setCellValue(key.length() > EXCEL_CELL_MAX_LEN ? "the content is too big" : key);

                Cell cellData1 = row.createCell(1);
                if (selectionskey != null)
                    cellData1.setCellValue(selectionskey.length() > EXCEL_CELL_MAX_LEN ? "the content is too big" : selectionskey);

                Cell cellData2 = row.createCell(2);
                if (selectionsMap.get(key) != null && selectionsMap.get(key).get(selectionskey) != null) {
                    String selectionsValues = extractSelectionValues("" + selectionsMap.get(key).get(selectionskey));
                    cellData2.setCellValue(selectionsValues.length() > EXCEL_CELL_MAX_LEN ? "the content is too big" : selectionsValues);
                }
            }

        }

    }

    private void fillDriversSheetWithData(Map<String, Map<String, Object>> driversMap, Workbook wb, Sheet sheet,
                                          String widgetName) {

        // CREATE BRANDED HEADER SHEET
        this.imageB64 = OrganizationImageManager.getOrganizationB64ImageWide(TenantManager.getTenant().getName());
        int startRow = 0;
        float rowHeight = 35; // in points
        int rowspan = 2;
        int startCol = 0;
        int colWidth = 25;
        int colspan = 2;
        int namespan = 10;
        int dataspan = 10;

        Row newheader;

        int headerIndex = createBrandedHeaderSheet(
                sheet,
                this.imageB64,
                startRow,
                rowHeight,
                rowspan,
                startCol,
                colWidth,
                colspan,
                namespan,
                dataspan,
                this.documentName,
                sheet.getSheetName());

        newheader = sheet.createRow((short) headerIndex + 1);

        Cell cell = newheader.createCell(0);
        cell.setCellValue("Filter");
        CellStyle headerCellStyle = buildCellStyle(sheet, true, HorizontalAlignment.LEFT, VerticalAlignment.CENTER, (short) 11);
        cell.setCellStyle(headerCellStyle);

        Cell cell2 = newheader.createCell(1);
        cell2.setCellValue("Value");
        cell2.setCellStyle(headerCellStyle);

        List<BIObject> allDocuments = getDocuments();
        List<BIObjectParameter> drivers = getDriversByDocumentName(allDocuments, this.documentName);

        int j = headerIndex + 2;
        for (String key : driversMap.keySet()) {

            Row row = sheet.createRow(j++);

            Cell cellData0 = row.createCell(0);
            String driverLabel = getDriverLabelByParameterUrlName(drivers, key) == null ? "" : getDriverLabelByParameterUrlName(drivers, key);
            cellData0.setCellValue(driverLabel.length() > EXCEL_CELL_MAX_LEN ?
                    "the content is too big" : driverLabel);

            Cell cellData1 = row.createCell(1);
            if (driversMap.get(key).keySet().contains("description")) {
                extractSelectionValues("" + driversMap.get(key).get("description"));
                String selectionValues = extractSelectionValues("" + driversMap.get(key).get("description"));
                cellData1.setCellValue(selectionValues.length() > EXCEL_CELL_MAX_LEN ?
                        "the content is too big" : selectionValues);
            } else {
                String selectionValues = extractSelectionValues("" + driversMap.get(key).get("value"));
                cellData1.setCellValue(selectionValues.length() > EXCEL_CELL_MAX_LEN ?
                        "the content is too big" : selectionValues);
            }
        }

    }

    private List<BIObject> getDocuments() {
        IBIObjectDAO documentsDao = null;
        List<BIObject> allDocuments = null;
        try {
            documentsDao = DAOFactory.getBIObjectDAO();
            allDocuments = documentsDao.loadAllBIObjects();
        } catch (Exception e) {
            LOGGER.debug("Documents objects can not be provided", e);
        }
        return allDocuments;
    }

    private List<BIObjectParameter> getDriversByDocumentName(List<BIObject> allDocuments, String documentName) {
        List<BIObjectParameter> drivers = null;
        try {
            for (BIObject document : allDocuments) {
                if (document.getName().equals(documentName)) {
                    drivers = document.getDrivers();
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Drivers objects can not be provided", e);
        }
        return drivers;
    }

    private String getDriverLabelByParameterUrlName(List<BIObjectParameter> drivers, String parameterUrlName) {
        String label = "";
        try {
            for (BIObjectParameter driver : drivers) {
                if (driver.getParameterUrlName().equals(parameterUrlName)) {
                    label = driver.getLabel();
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Driver label can not be provided", e);
        }
        return label;
    }

    private String extractSelectionValues(String selectionValues) {
        return selectionValues.replace("[\"(", "").replace(")\"]", "");
    }

    private JSONArray getChildFromWidgetContent(JSONObject widgetContent, String childName) {
        JSONArray ret = new JSONArray();
        if (widgetContent.has(childName)) {
            JSONArray childArray = widgetContent.optJSONArray(childName);
            if (childArray != null) {
                ret = childArray;
            } else {
                JSONObject childObject = widgetContent.optJSONObject(childName);
                if (childObject != null) {
                    ret.put(childObject);
                }
            }
        }
        return ret;
    }

    public void fillSheetWithData(JSONObject dataStore, Workbook wb, Sheet sheet, String widgetName, int offset,
                                  JSONObject settings) {
        try {
            JSONObject metadata = dataStore.getJSONObject("metaData");
            JSONArray columns = metadata.getJSONArray("fields");
            columns = filterDataStoreColumns(columns);
            JSONArray rows = dataStore.getJSONArray("rows");
            HashMap<String, Object> variablesMap = new HashMap<>();
            JSONObject widgetData = dataStore.getJSONObject("widgetData");
            JSONObject widgetContent = widgetData.getJSONObject("content");
            JSONArray columnSelectedOfDataset = getChildFromWidgetContent(widgetContent, "columnSelectedOfDataset");
//			HashMap<String, String> arrayHeader = new HashMap<>();
            HashMap<String, String> chartAggregationsMap = new HashMap<>();
            if (widgetData.getString("type").equalsIgnoreCase("table")) {
//				ATTENTION: renaming table columns names of the excel export has been placed at the end
//				for (int i = 0; i < columnSelectedOfDataset.length(); i++) {
//					JSONObject column = columnSelectedOfDataset.getJSONObject(i);
//					String key;
//					if (column.optBoolean("isCalculated") && !column.has("name")) {
//						key = column.getString("alias");
//					} else {
//						key = column.getString("name");
//					}
//					// arrayHeader is used to rename table columns names of the excel export
//					arrayHeader.put(key, column.getString("aliasToShow"));
//				}
            } else if (widgetData.getString("type").equalsIgnoreCase("chart")) {
                for (int i = 0; i < columnSelectedOfDataset.length(); i++) {
                    JSONObject column = columnSelectedOfDataset.getJSONObject(i);
                    if (column.has("aggregationSelected") && column.has("alias")) {
                        String col = column.getString("alias");
                        String aggregation = column.getString("aggregationSelected");
                        if (col.contains("$V")) {
                            if (body.has("COCKPIT_VARIABLES")) {
                                String columnAlias = "";
                                Pattern patt = Pattern.compile("(\\$V\\{)([\\w\\s]+)(\\})");
                                Matcher matcher = patt.matcher(col);
                                if (body.get("COCKPIT_VARIABLES") instanceof JSONObject) {
                                    JSONObject variableOBJ = body.getJSONObject("COCKPIT_VARIABLES");
                                    while (matcher.find()) {
                                        columnAlias = matcher.group(2);
                                    }
                                    col = col.replace("$V{" + columnAlias + "}", variableOBJ.getString(columnAlias));
                                } else {
                                    JSONArray arr = body.getJSONArray("COCKPIT_VARIABLES");
                                    for (int j = 0; j < arr.length(); j++) {
                                        JSONObject variableOBJ = arr.getJSONObject(j);
                                        while (matcher.find()) {
                                            columnAlias = matcher.group(2);
                                        }
                                        col = col.replace("$V{" + columnAlias + "}",
                                                variableOBJ.getString(columnAlias));
                                    }
                                }
                            }
                        }
                        chartAggregationsMap.put(col, aggregation);
                    }
                }
            }

            JSONArray columnsOrdered;
            if (widgetData.getString("type").equalsIgnoreCase("table") && widgetContent.has("columnSelectedOfDataset")) {
                hiddenColumns = getHiddenColumnsList(columnSelectedOfDataset);
                columnsOrdered = getTableOrderedColumns(columnSelectedOfDataset, columns);
            } else if (widgetData.getString("type").equalsIgnoreCase("discovery") && widgetContent.has("columnSelectedOfDataset")) {
                columnsOrdered = getDiscoveryOrderedColumns(columnSelectedOfDataset, columns);
            } else {
                columnsOrdered = columns;
            }

            JSONArray groupsFromWidgetContent = getGroupsFromWidgetContent(widgetData);
            Map<String, String> groupsAndColumnsMap = getGroupAndColumnsMap(widgetContent, groupsFromWidgetContent);

            // CREATE BRANDED HEADER SHEET
            this.imageB64 = OrganizationImageManager.getOrganizationB64ImageWide(TenantManager.getTenant().getName());
            int startRow = 0;
            float rowHeight = 35; // in points
            int rowspan = 2;
            int startCol = 0;
            int colWidth = 25;
            int colspan = 2;
            int namespan = 10;
            int dataspan = 10;

            if (offset == 0) { // if pagination is active, headers must be created only once
                Row header = null;

//				ATTENTION: exporting single widget must not be different from exporting whole cockpit
//				if (isSingleWidgetExport) { // export single widget
//					header = createHeaderColumnNames(sheet, groupsAndColumnsMap, columnsOrdered, 0);
//				} else { // export whole cockpit
//					// First row is for Widget name in case exporting whole Cockpit document
//					Row firstRow = sheet.createRow((short) 0);
//					Cell firstCell = firstRow.createCell(0);
//					firstCell.setCellValue(widgetName);
//					header = createHeaderColumnNames(sheet, groupsAndColumnsMap, columnsOrdered, 1);
//				}

                int headerIndex = createBrandedHeaderSheet(
                        sheet,
                        this.imageB64,
                        startRow,
                        rowHeight,
                        rowspan,
                        startCol,
                        colWidth,
                        colspan,
                        namespan,
                        dataspan,
                        this.documentName,
                        widgetName);

                header = createHeaderColumnNames(sheet, groupsAndColumnsMap, columnsOrdered, headerIndex + 1);

                for (int i = 0; i < columnsOrdered.length(); i++) {
                    JSONObject column = columnsOrdered.getJSONObject(i);
                    String columnName = column.getString("header");
                    String chartAggregation = null;
                    if (widgetData.getString("type").equalsIgnoreCase("table")) {
                        // renaming table columns names of the excel export
                        columnName = setColumnHeaderAlias(columnSelectedOfDataset, "aliasToShow", columnName);
                    } else if (widgetData.getString("type").equalsIgnoreCase("discovery")) {
                        // renaming table columns names of the excel export
                        columnName = setColumnHeaderAlias(columnSelectedOfDataset, "name", columnName);
                    } else if (widgetData.getString("type").equalsIgnoreCase("chart")) {
                        chartAggregation = chartAggregationsMap.get(columnName);
                        if (chartAggregation != null) {
                            columnName = columnName.split("_" + chartAggregation)[0];
                        }
                    }

                    columnName = getInternationalizedHeader(columnName);

                    if (widgetData.getString("type").equalsIgnoreCase("chart") && chartAggregation != null) {
                        columnName = columnName + "_" + chartAggregation;
                    }

                    Cell cell = header.createCell(i);
                    cell.setCellValue(columnName);

                    CellStyle headerCellStyle = buildCellStyle(sheet, true, HorizontalAlignment.LEFT, VerticalAlignment.CENTER, (short) 11);
                    cell.setCellStyle(headerCellStyle);
                }

                // adjusts the column width to fit the contents
                adjustColumnWidth(sheet, this.imageB64);
            }

            int numberOfSummaryRows = 0;
            List<String> summaryRowsLabels = new ArrayList<>();

            numberOfSummaryRows = doCockpitSummaryRowsLogic(widgetData, numberOfSummaryRows, summaryRowsLabels);
            boolean summaryLabelOnlyForPinnedColumns = isPinnedOnly(widgetData);


            // Cell styles for int and float
            CreationHelper createHelper = wb.getCreationHelper();

            DateFormat dateFormat = new SimpleDateFormat(DATE_FORMAT, getLocale());

            SimpleDateFormat timeStampFormat = new SimpleDateFormat(TIMESTAMP_FORMAT, getLocale());

            // cell styles for table widget
            JSONObject[] columnStyles = new JSONObject[columnsOrdered.length() + 10];
            HashMap<String, String> mapColumns = new HashMap<>();
            HashMap<String, String> mapColumnsTypes = new HashMap<>();
            HashMap<String, Object> mapParameters = new HashMap<>();
            if (widgetData.getString("type").equalsIgnoreCase("table")) {
                columnStyles = getColumnsStyles(columnsOrdered, widgetContent);
                mapColumns = getColumnsMap(columnsOrdered);
                mapColumnsTypes = getColumnsMapTypes(columnsOrdered);
                mapParameters = createMapParameters(mapParameters);
            }
            variablesMap = createMapVariables(variablesMap);
            // FILL RECORDS
            int isGroup = groupsAndColumnsMap.isEmpty() ? 0 : 1;
            for (int r = 0; r < rows.length(); r++) {
                JSONObject rowObject = rows.getJSONObject(r);
                Row row;

//				if (isSingleWidgetExport)
//					row = sheet.createRow((offset + r + isGroup) + 1); // starting from second row, because the 0th (first) is Header
//				else
//					row = sheet.createRow((offset + r + isGroup) + 2);

                if (StringUtils.isNotEmpty(imageB64)) {
                    row = sheet.createRow((offset + r + isGroup) + (startRow + rowspan) + 2); // starting by Header
                } else {
                    row = sheet.createRow((offset + r + isGroup) + 2);
                }

                boolean isSummaryRow = isSummaryRow(r, rows, numberOfSummaryRows);

                for (int c = 0; c < columnsOrdered.length(); c++) {
                    JSONObject column = columnsOrdered.getJSONObject(c);
                    String type = getCellType(column, column.getString("name"));
                    String colIndex = column.getString("name"); // column_1, column_2, column_3...

                    Cell cell = row.createCell(c);
                    Object value = rowObject.get(colIndex);

                    if (value != null && !isSummaryRow) {
                        String s = value.toString();
                        switch (type) {
                            case "string":
                                cell.setCellValue(s);
                                cell.setCellStyle(getStringCellStyle(wb, createHelper, column, columnStyles[c],
                                        FLOAT_CELL_DEFAULT_FORMAT, settings, s, rowObject, mapColumns, mapColumnsTypes,
                                        variablesMap, mapParameters));
                                break;
                            case "int":
                                if (!s.trim().isEmpty()) {
                                    cell.setCellValue(Integer.parseInt(s));
                                    cell.setCellStyle(getIntCellStyle(wb, createHelper, column, columnStyles[c],
                                            INT_CELL_DEFAULT_FORMAT, settings, Integer.parseInt(s), rowObject, mapColumns,
                                            mapColumnsTypes, variablesMap, mapParameters));
                                } else {
                                    cell.setCellStyle(getGenericCellStyle(wb, createHelper, column, columnStyles[c],
                                            INT_CELL_DEFAULT_FORMAT, settings, rowObject, mapColumns, mapColumnsTypes,
                                            variablesMap, mapParameters));
                                }
                                break;
                            case "float":
                                if (!s.trim().isEmpty()) {
                                    cell.setCellValue(Double.parseDouble(s));
                                    cell.setCellStyle(getDoubleCellStyle(wb, createHelper, column, columnStyles[c],
                                            FLOAT_CELL_DEFAULT_FORMAT, settings, Double.parseDouble(s), rowObject,
                                            mapColumns, mapColumnsTypes, variablesMap, mapParameters));
                                } else {
                                    cell.setCellStyle(getGenericCellStyle(wb, createHelper, column, columnStyles[c],
                                            FLOAT_CELL_DEFAULT_FORMAT, settings, rowObject, mapColumns, mapColumnsTypes,
                                            variablesMap, mapParameters));
                                }
                                break;
                            case "date":
                                try {
                                    if (!s.trim().isEmpty()) {
                                        Date date = dateFormat.parse(s);
                                        cell.setCellValue(date);
                                        cell.setCellStyle(getDateCellStyle(wb, createHelper, column, columnStyles[c],
                                                DATE_FORMAT, settings, rowObject, mapColumns, mapColumnsTypes, variablesMap,
                                                mapParameters));
                                    }
                                } catch (Exception e) {
                                    LOGGER.debug("Date will be exported as string due to error: ", e);
                                    cell.setCellValue(s);
                                }
                                break;
                            case "timestamp":
                                try {
                                    if (!s.trim().isEmpty()) {
                                        Date ts = timeStampFormat.parse(s);
                                        cell.setCellValue(ts);
                                        cell.setCellStyle(getDateCellStyle(wb, createHelper, column, columnStyles[c],
                                                TIMESTAMP_FORMAT, settings, rowObject, mapColumns, mapColumnsTypes,
                                                variablesMap, mapParameters));
                                    }
                                } catch (Exception e) {
                                    LOGGER.debug("Timestamp will be exported as string due to error: ", e);
                                    cell.setCellValue(s);
                                }
                                break;
                            default:
                                cell.setCellValue(s);
                                break;
                        }
                    } else if (value != null) {
                        String summaryRowLabel = summaryRowsLabels.get(r - (rows.length() - numberOfSummaryRows));
                        setSummaryRowValue(columnStyles, c, value, cell, summaryRowLabel, (JSONObject) columnSelectedOfDataset.get(c), summaryLabelOnlyForPinnedColumns);
                    }
                }
            }

        } catch (Exception e) {
            throw new SpagoBIRuntimeException("Cannot write data to Excel file", e);
        }
    }


    private String setColumnHeaderAlias(JSONArray columnSelectedOfDataset, String aliasToShow, String columnName) throws JSONException {
        for (int j = 0; j < columnSelectedOfDataset.length(); j++) {
            JSONObject columnSelected = columnSelectedOfDataset.getJSONObject(j);
            if (columnSelected.has(aliasToShow) && columnName.equals(columnSelected.getString(aliasToShow))) {
                columnName = getTableColumnHeaderValue(columnSelected);
                break;
            }
        }
        return columnName;
    }


    private boolean isSummaryRow(int r, JSONArray rows, int numberOfSummaryRows) {
        return r >= rows.length() - numberOfSummaryRows;
    }

    private static void setSummaryRowValue(JSONObject[] columnStyles, int c, Object value, Cell cell, String summaryRowLabel, JSONObject column, boolean isOnlyPinned) {
        try {
            int precision = (columnStyles[c] != null && columnStyles[c].has("precision") && columnStyles[c].optInt("precision") != 0) ? columnStyles[c].getInt("precision") : 2;
            String formattedValue;
            try {
                formattedValue = new DecimalFormat("#,##0." + StringUtils.repeat("0", precision)).format(value);
            } catch (Exception e) {
                formattedValue = value.toString();
            }
            String valueWithLabel = !summaryRowLabel.isEmpty() ? summaryRowLabel.concat(" ").concat(formattedValue) : formattedValue;
            if (!isOnlyPinned) {
                if (column.has("pinned") || value.equals("")) {
                    cell.setCellValue(formattedValue);
                } else {
                    cell.setCellValue(valueWithLabel);
                }
            } else {
                if (column.has("pinned")) {
                    cell.setCellValue(valueWithLabel);
                } else {
                    cell.setCellValue(formattedValue);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error while exporting summary row: ", e);
            cell.setCellValue(value.toString());
        }
    }

    private int doCockpitSummaryRowsLogic(JSONObject widgetData, int numberOfSummaryRows, List<String> summaryRowsLabels) throws JSONException {
        if (cockpitSummaryRowsEnabled(widgetData)) {
            JSONArray list = widgetData.getJSONObject("settings").getJSONObject("summary").getJSONArray("list");
            numberOfSummaryRows = list.length();

            for (int i = 0; i < numberOfSummaryRows; i++) {
                summaryRowsLabels.add(list.getJSONObject(i).getString("label"));
            }

        }
        return numberOfSummaryRows;
    }

    private boolean isPinnedOnly(JSONObject widgetData) throws JSONException {
        if (cockpitSummaryRowsEnabled(widgetData) && widgetData.getJSONObject("settings").getJSONObject("summary").has("style")) {
            return widgetData.getJSONObject("settings").getJSONObject("summary").getJSONObject("style").getBoolean("pinnedOnly");
        }
        return false;
    }


    private boolean cockpitSummaryRowsEnabled(JSONObject widgetData) {
        try {
            return widgetData.has("settings") && widgetData.getJSONObject("settings").has("summary") && widgetData.getJSONObject("settings").getJSONObject("summary").getBoolean("enabled");
        } catch (JSONException e) {
            return false;
        }
    }

    private HashMap<String, Object> createMapVariables(HashMap<String, Object> variablesMap) throws JSONException {
        if (body.has("COCKPIT_VARIABLES")) {
            if (body.get("COCKPIT_VARIABLES") instanceof JSONObject) {
                JSONObject variableOBJ = body.getJSONObject("COCKPIT_VARIABLES");
                variablesMap = new Gson().fromJson(variableOBJ.toString(), HashMap.class);

            } else if (body.get("COCKPIT_VARIABLES") instanceof JSONArray) {

                for (int j = 0; j < body.getJSONArray("COCKPIT_VARIABLES").length(); j++) {
                    JSONObject variableOBJ = body.getJSONArray("COCKPIT_VARIABLES").getJSONObject(j);
                    variablesMap = new Gson().fromJson(variableOBJ.toString(), HashMap.class);

                }

            }

        }
        return variablesMap;
    }

    private HashMap<String, Object> createMapParameters(HashMap<String, Object> mapParameters) throws JSONException {
        if (body.has("COCKPIT_SELECTIONS") && body.get("COCKPIT_SELECTIONS") instanceof JSONObject
                && body.getJSONObject("COCKPIT_SELECTIONS").has("drivers")) {
            mapParameters = getParametersMap(body.getJSONObject("COCKPIT_SELECTIONS").getJSONObject("drivers"));
        } else if (body.has("COCKPIT_SELECTIONS") && body.get("COCKPIT_SELECTIONS") instanceof JSONArray) {
            for (int j = 0; j < body.getJSONArray("COCKPIT_SELECTIONS").length(); j++) {
                if ((body.getJSONArray("COCKPIT_SELECTIONS").get(j) instanceof JSONArray)
                        && (!(body.getJSONArray("COCKPIT_SELECTIONS").get(j) instanceof JSONArray))
                        && body.getJSONArray("COCKPIT_SELECTIONS").getJSONObject(j).has("drivers")) {
                    mapParameters = getParametersMap(
                            body.getJSONArray("COCKPIT_SELECTIONS").getJSONObject(j).getJSONObject("drivers"));
                }
            }
        }
        return mapParameters;
    }

    private HashMap<String, String> getColumnsMap(JSONArray columnsOrdered) {
        HashMap<String, String> mapp = new HashMap<>();
        for (int c = 0; c < columnsOrdered.length(); c++) {
            try {
                JSONObject column = columnsOrdered.getJSONObject(c);

                mapp.put(column.getString("header"), column.getString("name"));
            } catch (JSONException e) {
                throw new SpagoBIRuntimeException("Couldn't create columns map", e);
            }
        }
        return mapp;
    }

    private HashMap<String, String> getColumnsMapTypes(JSONArray columnsOrdered) {
        HashMap<String, String> mapp = new HashMap<>();
        for (int c = 0; c < columnsOrdered.length(); c++) {
            try {
                JSONObject column = columnsOrdered.getJSONObject(c);

                mapp.put(column.getString("name"), column.getString("type"));
            } catch (JSONException e) {
                throw new SpagoBIRuntimeException("Couldn't create columns map", e);
            }
        }
        return mapp;
    }

    private HashMap<String, Object> getParametersMap(JSONObject drivers) {
        HashMap<String, Object> mapp = new HashMap<>();

        Iterator<String> keys = drivers.keys();

        while (keys.hasNext()) {
            String key = keys.next();
            try {
                if (drivers.get(key) instanceof JSONArray) {
                    JSONArray parameterArray = drivers.getJSONArray(key);
                    for (int c = 0; c < parameterArray.length(); c++) {

                        JSONObject paramValueOBJ = parameterArray.getJSONObject(c);

                        Iterator<String> paramkeys = paramValueOBJ.keys();
                        while (paramkeys.hasNext()) {
                            String paramkey = paramkeys.next();
                            mapp.put(key, paramValueOBJ.get(paramkey));

                        }

                    }

                }
            } catch (JSONException e) {
                throw new SpagoBIRuntimeException("Couldn't create parameter map", e);
            }
        }

        return mapp;
    }


    private Map<String, Map<String, Object>> createSelectionsMap() throws JSONException {
        Map<String, Map<String, Object>> selectionsMap = new HashMap<>();
        if (body.has("COCKPIT_SELECTIONS") && body.get("COCKPIT_SELECTIONS") instanceof JSONArray) {
            JSONArray cockpitSelections = body.getJSONArray("COCKPIT_SELECTIONS");

            for (int i = 0; i < cockpitSelections.length(); i++) {
                if (!(cockpitSelections.get(i) instanceof JSONArray)) {
                    JSONObject cockpitSelection = cockpitSelections.getJSONObject(i);

                    manageUserSelectionFromJSONObject(selectionsMap, cockpitSelection);
                }
            }
        } else if (body.has("COCKPIT_SELECTIONS") && body.get("COCKPIT_SELECTIONS") instanceof JSONObject) {

            JSONObject cockpitSelection = body.getJSONObject("COCKPIT_SELECTIONS");
            manageUserSelectionFromJSONObject(selectionsMap, cockpitSelection);
        }

        return selectionsMap;
    }

    private void manageUserSelectionFromJSONObject(Map<String, Map<String, Object>> selectionsMap,
                                                   JSONObject cockpitSelection) throws JSONException {
        if (cockpitSelection.has("userSelections") && !((cockpitSelection.getJSONObject("userSelections")).length() == 0)) {
            manageUserSelectionFromJSONObjectUsingKey(selectionsMap, cockpitSelection, "userSelections");
        } else if (cockpitSelection.has("selections") && !((cockpitSelection.getJSONObject("selections")).length() == 0)) {
            // TODO : Map widget seems to have a different syntax
            manageUserSelectionFromJSONObjectUsingKey(selectionsMap, cockpitSelection, "selections");
        }
    }

    private void manageUserSelectionFromJSONObjectUsingKey(Map<String, Map<String, Object>> selectionsMap,
                                                           JSONObject cockpitSelection, String key) throws JSONException {
        JSONObject selections = cockpitSelection.getJSONObject(key);

        Iterator<String> keys = selections.keys();

        manageSingleUserSelection(selectionsMap, selections, keys);
    }

    private void manageSingleUserSelection(Map<String, Map<String, Object>> selectionsMap, JSONObject selections,
                                           Iterator<String> keys) throws JSONException {
        while (keys.hasNext()) {
            String key = keys.next();
            if (selections.get(key) instanceof JSONObject) {
                manageSelection(selectionsMap, selections, key);
            }
        }
    }


    private void manageSelection(Map<String, Map<String, Object>> selectionsMap, JSONObject selections, String key)
            throws JSONException {
        JSONObject selection = (JSONObject) selections.get(key);
        Iterator<String> selectionKeys = selection.keys();
        HashMap<String, Object> selects = new HashMap<>();
        while (selectionKeys.hasNext()) {
            String selKey = selectionKeys.next();
            Object select = selection.get(selKey);
            if (!selKey.contains(",")) {
                manageUserSelectionValue(selects, selKey, select);
            }
        }
        if (!selects.isEmpty()) {
            selectionsMap.put(key, selects);
        }
    }

    private void manageUserSelectionValue(HashMap<String, Object> selects, String selKey, Object select)
            throws JSONException {
        if (select instanceof JSONObject) {
            if (((JSONObject) select).has("filterOperator")) {
                // Do nothing
            }
        } else {
            if (select instanceof JSONArray) {
                JSONArray selectArray = (JSONArray) select;
                for (int j = 0; j < selectArray.length(); j++) {
                    Object selObj = selectArray.get(j);
                    if (selObj instanceof JSONObject) {
                        if (((JSONObject) selObj).has("filterOperator")) {
                            // Do nothing
                        } else {
                            selects.put(selKey, selObj);
                        }

                    } else {
                        selects.put(selKey, selObj);
                    }
                }
            } else {
                selects.put(selKey, select);
            }
        }
    }

    private Map<String, Map<String, Object>> createDriversMap() throws JSONException {
        Map<String, Map<String, Object>> selectionsMap = new HashMap<>();
        if (body.has("COCKPIT_SELECTIONS") && body.get("COCKPIT_SELECTIONS") instanceof JSONArray) {
            JSONArray cockpitSelections = body.getJSONArray("COCKPIT_SELECTIONS");

            for (int i = 0; i < cockpitSelections.length(); i++) {
                if (!(cockpitSelections.get(i) instanceof JSONArray)) {
                    JSONObject cockpitSelection = cockpitSelections.getJSONObject(i);

                    manageDriversFromJSONObject(selectionsMap, cockpitSelection);

                }
            }
        } else if (body.has("COCKPIT_SELECTIONS") && body.get("COCKPIT_SELECTIONS") instanceof JSONObject) {

            JSONObject cockpitSelection = body.getJSONObject("COCKPIT_SELECTIONS");
            manageDriversFromJSONObject(selectionsMap, cockpitSelection);
        }

        return selectionsMap;
    }

    private void manageDriversFromJSONObject(Map<String, Map<String, Object>> selectionsMap,
                                             JSONObject cockpitSelection) throws JSONException {
        if (cockpitSelection.has("drivers")) {
            manageDriversFromJSONObjectUsingKey(selectionsMap, cockpitSelection, "drivers");
        }
    }

    private void manageDriversFromJSONObjectUsingKey(Map<String, Map<String, Object>> selectionsMap,
                                                     JSONObject cockpitSelection, String key) throws JSONException {
        JSONObject drivers = cockpitSelection.getJSONObject(key);

        Iterator<String> keys = drivers.keys();

        manageSingleDriver(selectionsMap, drivers, keys);
    }

    private void manageSingleDriver(Map<String, Map<String, Object>> selectionsMap, JSONObject drivers,
                                    Iterator<String> keys) throws JSONException {
        while (keys.hasNext()) {
            String key = keys.next();
            if (drivers.get(key) instanceof JSONArray) {
                manageDriver(selectionsMap, drivers, key);
            }
        }
    }

    private void manageDriver(Map<String, Map<String, Object>> selectionsMap, JSONObject drivers, String key)
            throws JSONException {
        JSONArray driver = (JSONArray) drivers.get(key);
        Iterator<String> driverKeys = ((JSONObject) driver.get(0)).keys();
        HashMap<String, Object> selects = new HashMap<>();
        while (driverKeys.hasNext()) {
            String selKey = driverKeys.next();
            Object select = ((JSONObject) driver.get(0)).get(selKey);
            if (!selKey.contains(",") && !select.toString().isEmpty()) {
                manageUserSelectionValue(selects, selKey, select);
            }
        }
        if (!selects.isEmpty()) {
            selectionsMap.put(key, selects);
        }
    }

    private String getCellType(JSONObject column, String colName) {
        try {
            return column.getString("type");
        } catch (Exception e) {
            LOGGER.error("Error while retrieving column {} type. It will be treated as string.", colName, e);
            return "string";
        }
    }

    @Override
    protected String getNumberFormatByPrecision(int precision, String initialFormat) {
        StringBuilder format = new StringBuilder(initialFormat);
        if (precision > 0) {
            format.append(".");
            format.append("0".repeat(precision));
        }
        return format.toString();
    }

    private Row createHeaderColumnNames(Sheet sheet, Map<String, String> groupsAndColumnsMap, JSONArray columnsOrdered,
                                        int startRowOffset) {
        try {
            Row header = null;
            if (!groupsAndColumnsMap.isEmpty()) {
                Row newheader = sheet.createRow((short) startRowOffset);
                for (int i = 0; i < columnsOrdered.length(); i++) {
                    JSONObject column = columnsOrdered.getJSONObject(i);
                    String groupName = groupsAndColumnsMap.get(column.get("header"));
                    if (groupName != null) {
                        // check if adjacent header cells have same group names in order to add merged region
                        int adjacents = getAdjacentEqualNamesAmount(groupsAndColumnsMap, columnsOrdered, i, groupName);
                        if (adjacents > 1) {
                            sheet.addMergedRegion(new CellRangeAddress(newheader.getRowNum(), // first row (0-based)
                                    newheader.getRowNum(), // last row (0-based)
                                    i, // first column (0-based)
                                    i + adjacents - 1 // last column (0-based)
                            ));
                        }
                        Cell cell = newheader.createCell(i);
                        cell.setCellValue(groupName);
                        i += adjacents - 1;
                    }
                }
                header = sheet.createRow((short) (startRowOffset + 1));
            } else {
                header = sheet.createRow((short) startRowOffset); // first row
            }
            return header;
        } catch (Exception e) {
            throw new SpagoBIRuntimeException("Couldn't create header column names", e);
        }
    }

    private int getAdjacentEqualNamesAmount(Map<String, String> groupsAndColumnsMap, JSONArray columnsOrdered, int matchStartIndex, String groupNameToMatch) {
        try {
            int adjacents = 0;
            for (int i = matchStartIndex; i < columnsOrdered.length(); i++) {
                JSONObject column = columnsOrdered.getJSONObject(i);
                String groupName = groupsAndColumnsMap.get(column.get("header"));
                if (groupName != null && groupName.equals(groupNameToMatch)) {
                    adjacents++;
                } else {
                    return adjacents;
                }
            }
            return adjacents;
        } catch (Exception e) {
            throw new SpagoBIRuntimeException("Couldn't compute adjacent equal names amount", e);
        }
    }

    public Sheet createUniqueSafeSheet(Workbook wb, String widgetName, String cockpitSheetName) {
        Sheet sheet;
        String sheetName;
        try {
            if (!isSingleWidgetExport && cockpitSheetName != null && !cockpitSheetName.isEmpty()) {
                sheetName = cockpitSheetName.concat(".").concat(widgetName);
            } else {
                sheetName = widgetName;
            }
            String safeSheetName = WorkbookUtil.createSafeSheetName(sheetName);
            if (safeSheetName.length() +
                    "(".length() + String.valueOf(uniqueId).length() + "(".length() > SHEET_NAME_MAX_LEN) {
                safeSheetName = safeSheetName.substring(0, safeSheetName.length() -
                        "(".length() - String.valueOf(uniqueId).length() - ")".length());
            }
            String uniqueSafeSheetName = safeSheetName/* + String.valueOf(uniqueId)*/;
            try {
                sheet = wb.createSheet(uniqueSafeSheetName);
                uniqueId++;
                return sheet;
            } catch (Exception e) {
                sheet = wb.createSheet(uniqueSafeSheetName + "(" + uniqueId + ")");
                uniqueId++;
                return sheet;
            }
        } catch (Exception e) {
            throw new SpagoBIRuntimeException("Couldn't create sheet", e);
        }
    }

    private Sheet createUniqueSafeSheetForSelections(Workbook wb, String widgetName) {
        Sheet sheet;
        try {
            sheet = wb.createSheet(widgetName);
            return sheet;
        } catch (Exception e) {
            throw new SpagoBIRuntimeException("Couldn't create sheet", e);
        }
    }

    private Sheet createUniqueSafeSheetForDrivers(Workbook wb, String widgetName) {
        Sheet sheet;
        try {
            sheet = wb.createSheet(widgetName);
            return sheet;
        } catch (Exception e) {
            throw new SpagoBIRuntimeException("Couldn't create sheet", e);
        }
    }

    private JSONObject getDatastore(String datasetLabel, Map<String, Object> map, String selections) {
        // if pagination is disabled offset = 0, fetchSize = -1
        return getDatastore(datasetLabel, map, selections, 0, -1);
    }
}

