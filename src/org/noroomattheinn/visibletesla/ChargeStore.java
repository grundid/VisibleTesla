/*
 * ChargeStore.java - Copyright(c) 2014 Joe Pasqua
 * Provided under the MIT License. See the LICENSE file for details.
 * Created: Oct 23, 2014
 */
package org.noroomattheinn.visibletesla;

import com.google.common.collect.Range;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javafx.scene.control.Dialogs;
import javafx.stage.FileChooser;
import jxl.Workbook;
import jxl.write.DateFormat;
import jxl.write.WritableCellFormat;
import jxl.write.WritableFont;
import jxl.write.WritableSheet;
import jxl.write.WritableWorkbook;
import jxl.write.WriteException;
import org.apache.commons.lang3.StringUtils;
import static org.noroomattheinn.tesla.Tesla.logger;
import org.noroomattheinn.visibletesla.dialogs.DateRangeDialog;

/**
 * ChargeStore: Manage persistent storage for Charge Cycle information.
 * 
 * @author Joe Pasqua <joe at NoRoomAtTheInn dot org>
 */
public class ChargeStore implements ThreadManager.Stoppable {
/*------------------------------------------------------------------------------
 *
 * Internal  State
 * 
 *----------------------------------------------------------------------------*/
    
    private final AppContext ac;
    private final File chargeFile;
    private final PrintStream chargeWriter;
    
/*==============================================================================
 * -------                                                               -------
 * -------              Public Interface To This Class                   ------- 
 * -------                                                               -------
 *============================================================================*/
    
    public ChargeStore(AppContext appContext)
            throws FileNotFoundException {
        this.ac = appContext;
        this.chargeFile = new File(ac.appFileFolder(), ac.vehicle.getVIN()+".charge.json");
        
        FileOutputStream fos = new FileOutputStream(chargeFile, true);
        chargeWriter = new PrintStream(fos);
        
        ac.lastChargeCycle.addTracker(false, new Runnable() {
            @Override public void run() {
                chargeWriter.println(ac.lastChargeCycle.get().toJSONString());
                submitData(ac.lastChargeCycle.get());
            }
        });
        
        ac.tm.addStoppable((ThreadManager.Stoppable)this);
    }
   
    public List<ChargeCycle> loadCharges(Range<Long> period) {
        List<ChargeCycle> charges = new ArrayList<>();
        try {
            BufferedReader r = new BufferedReader(new FileReader(chargeFile));

            try {
                String entry;
                while ((entry = r.readLine()) != null) {
                    ChargeCycle cycle = ChargeCycle.fromJSON(entry);
                    if (period == null || period.contains(cycle.startTime)) {
                        charges.add(cycle);
                    }
                }
            } catch (IOException ex) {
                logger.warning("Problem reading Charge Cycle data: " + ex);
            }
        } catch (FileNotFoundException ex) {
            logger.warning("Could not open Charge file: " + ex);
        }
        return charges;
    }
    
    public void exportCSV() {        
        String initialDir = ac.persistentState.get(
                StatsCollector.LastExportDirKey, System.getProperty("user.home"));
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Charge Data");
        fileChooser.setInitialDirectory(new File(initialDir));

        File file = fileChooser.showSaveDialog(ac.stage);
        if (file != null) {
            String enclosingDirectory = file.getParent();
            if (enclosingDirectory != null)
                ac.persistentState.put(StatsCollector.LastExportDirKey, enclosingDirectory);
            Range<Long> exportPeriod = DateRangeDialog.getExportPeriod(ac.stage);
            if (exportPeriod == null)
                return;
            export(file, exportPeriod);
        }
    }
    
    @Override public void stop() { chargeWriter.close(); }
    
/*------------------------------------------------------------------------------
 *
 * PRIVATE Methods for submitting anonymous data
 * 
 *----------------------------------------------------------------------------*/
    
    private static final String VTDataAddress = "data@visibletesla.com";
    private static final String VTChargeDataSubj = "Charge Data Submission";
    
    private void submitData(ChargeCycle cycle) {
        if (!ac.prefs.submitAnonData.get()) return;
        ditherLocation(cycle);
        String jsonRep = cycle.toJSONString();
        
        // Get ready to tack on a few more fields by getting rid of closing curly
        jsonRep = StringUtils.substringBefore(jsonRep, "}");
        
        // Concatenate the extra fields and put back the closing curly
        String body = String.format("%s, \"battery\": \"%s\", \"uuid\": \"%s\" }", 
                jsonRep, ac.vehicle.getOptions().batteryType(), ac.vehicle.getUUID());
        
        // Send the notification and log the body
        ac.mailer.send(VTDataAddress, VTChargeDataSubj, body);
        logger.info("Charge data submitted: " + body);
    }
    
    private void ditherLocation(ChargeCycle cycle) {
        if (!ac.prefs.includeLocData.get()) { cycle.lat = cycle.lng = 0; return; }
        if (cycle.superCharger || (cycle.lat == 0 && cycle.lng == 0)) return;
        
        double random, offset;
        double ditherAmt = ac.prefs.ditherLocAmt.get();
        double pow = Math.pow(10, ditherAmt);       // 10^ditherAmt
        
        random = 0.5 + (Math.random()/2);           // value in [0.5, 1]
        offset = (random/pow) * (Math.random() > 0.5 ? -1 : 1);
        cycle.lat += offset;

        random = 0.5 + (Math.random()/2);           // value in [0.5, 1]
        offset = (random/pow) * (Math.random() > 0.5 ? -1 : 1);
        cycle.lng += offset;
    }
        
/*------------------------------------------------------------------------------
 *
 * PRIVATE Methods for writing to an excel file
 * 
 *----------------------------------------------------------------------------*/
    
    private void export(File file, Range<Long> exportPeriod) {
        List<ChargeCycle> charges = loadCharges(exportPeriod);

        try {
            WritableWorkbook workbook = Workbook.createWorkbook(file);
            WritableSheet sheet = workbook.createSheet("Sheet1", 0);
            
            int row = 0;
            addTableHeader(sheet, row++);
            for (ChargeCycle cycle : charges) {
                emitRow(sheet, row++, cycle);
            }
            workbook.write();
            workbook.close();

            Dialogs.showInformationDialog(
                    ac.stage, "Your data has been exported",
                    "Data Export Process" , "Export Complete");
        } catch (IOException | WriteException ex) {
            Dialogs.showErrorDialog(
                    ac.stage, "Unable to save to: " + file,
                    "Data Export Process" , "Export Failed");
        }
        
    }
    
    private static final WritableFont StdFont = new WritableFont(WritableFont.ARIAL, 12); 	
    private static final WritableFont HdrFont = new WritableFont(WritableFont.ARIAL, 12, WritableFont.BOLD); 	
    private static final WritableCellFormat StdFmt = new WritableCellFormat(StdFont);
    private static final WritableCellFormat HdrFmt = new WritableCellFormat(HdrFont);
    private static final DateFormat df = new jxl.write.DateFormat("M/d/yy H:mm:ss");
    private static final WritableCellFormat dateFormat = new jxl.write.WritableCellFormat(df);
    static { dateFormat.setFont(StdFont); }
    
    private void emitRow(WritableSheet sheet, int row, ChargeCycle cycle)
            throws WriteException {
        int column = 0;
        sheet.addCell(new jxl.write.DateTime(column++, row, new Date(cycle.startTime), dateFormat));
        sheet.addCell(new jxl.write.DateTime(column++, row, new Date(cycle.endTime), dateFormat));
        sheet.addCell(new jxl.write.Boolean(column++, row, cycle.superCharger, StdFmt));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.phases, StdFmt));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.startRange, StdFmt));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.endRange, StdFmt));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.startSOC, StdFmt));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.endSOC, StdFmt));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.lat, StdFmt));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.lng, StdFmt));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.odometer, StdFmt));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.peakVoltage, StdFmt));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.avgVoltage, StdFmt));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.peakCurrent, StdFmt));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.avgCurrent, StdFmt));
        sheet.addCell(new jxl.write.Number(column++, row, cycle.energyAdded, StdFmt));
    }
    
    private void addTableHeader(WritableSheet sheet, int row) throws WriteException {
        final String[] labels = {
            "Start Date/Time", "Ending Date/Time", "Supercharger?", "Phases", "Start Range",
            "End Range", "Start SOC", "End SOC", "(Latitude, ", " Longitude)", "Odometer",
            "Peak V", "Avg V", "Peak I", "Avg I", "Energy"};
        
        for (int column = 0; column < labels.length; column++) {
            String label = labels[column];
            sheet.setColumnView(column, label.length()+3);
            sheet.addCell(new jxl.write.Label(column, row, label, HdrFmt));
        }
        
        // Make the header row stationary
        sheet.getSettings().setVerticalFreeze(1);
    }
}
