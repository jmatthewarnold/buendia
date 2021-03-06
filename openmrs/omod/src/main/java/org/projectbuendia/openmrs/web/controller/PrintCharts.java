// Copyright 2015 The Project Buendia Authors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License.  You may obtain a copy
// of the License at: http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distrib-
// uted under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied.  See the License for
// specific language governing permissions and limitations under the License.

package org.projectbuendia.openmrs.web.controller;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.Form;
import org.openmrs.FormField;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.Person;
import org.openmrs.api.ObsService;
import org.openmrs.api.OrderService;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.projectbuendia.ClientConceptNamer;
import org.openmrs.projectbuendia.Utils;
import org.openmrs.projectbuendia.VisitObsValue;
import org.openmrs.projectbuendia.webservices.rest.ChartResource;
import org.openmrs.projectbuendia.webservices.rest.DbUtil;
import org.openmrs.projectbuendia.webservices.rest.OrderResource;
import org.openmrs.util.FormUtil;
import org.openmrs.util.OpenmrsUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** The controller for the profile management page. */
@Controller
public class PrintCharts {
    protected static Log log = LogFactory.getLog(ProfileManager.class);

    private static final Comparator<Patient> PATIENT_COMPARATOR = new Comparator<Patient>() {
        @Override public int compare(Patient p1, Patient p2) {
            PatientIdentifier id1 = p1.getPatientIdentifier("MSF");
            PatientIdentifier id2 = p2.getPatientIdentifier("MSF");
            return Utils.alphanumericComparator.compare(
                id1 == null ? null : id1.getIdentifier(),
                id2 == null ? null : id2.getIdentifier()
            );
        }
    };

    private boolean authorized() {
        return Context.hasPrivilege("Manage Concepts") &&
            Context.hasPrivilege("Manage Forms");
    }

    public static final DateFormat HEADER_DATE_FORMAT = new SimpleDateFormat("d MMM");
    private static final DateFormat ORDER_DATE_FORMAT = HEADER_DATE_FORMAT;

    /** Use the client (profile) strings if we can. */
    private static final ClientConceptNamer NAMER =
            new ClientConceptNamer(ClientConceptNamer.DEFAULT_CLIENT);

    private static final String BASIC_AUTH_MODE = "Basic ";

    private static final VisitObsValue.ObsValueVisitor<String> STRING_VISITOR =
        new VisitObsValue.ObsValueVisitor<String>() {
            @Override public String visitCoded(Concept value) {
                return NAMER.getClientName(value);
            }

            @Override public String visitNumeric(Double value) {
                return Double.toString(value);
            }

            @Override public String visitBoolean(Boolean value) {
                return Boolean.toString(value);
            }

            @Override public String visitText(String value) {
                return value;
            }

            @Override public String visitDate(Date d) {
                return Utils.YYYYMMDD_UTC_FORMAT.format(d);
            }

            @Override public String visitDateTime(Date d) {
                return Utils.SPREADSHEET_FORMAT.format(d);
            }
        };

    /** This is executed every time a request is made. */
    @ModelAttribute
    public void onStart() {}

    @RequestMapping(
            value = "/module/projectbuendia/openmrs/print-charts",
            method = RequestMethod.GET)
    public void get(HttpServletRequest request, ModelMap model) {
        model.addAttribute("authorized", authorized());
    }



    @RequestMapping(
            value = "/module/projectbuendia/openmrs/printable",
            method = {RequestMethod.POST, RequestMethod.GET})
    public void post(HttpServletRequest request, HttpServletResponse response, ModelMap model) {
        // Attempt authentication if basic HTML auth is available.
        // TODO: it would be better to do this in a more legitimate part of the stack, but I've got
        // no idea where that would be.
        tryBasicAuth(request);
        model.addAttribute("authorized", authorized());
        try {
            try {
                generateExport(request, response, model);
            } catch (NoProfileException e) {
                response.getWriter().write(
                        "No profile loaded. Please load a profile before exporting data.");
            }
        } catch (IOException e) {
            // OpenMRS prints the stack trace when this happens. WIN.
            throw new RuntimeException(e);
        }
    }

    private void tryBasicAuth(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return;
        }
        if (!header.startsWith(BASIC_AUTH_MODE)) {
            return;
        }
        String base64 = header.substring(BASIC_AUTH_MODE.length());
        String authString = new String(Base64.decodeBase64(base64));
        String[] credentials = authString.split(":");
        if (credentials.length != 2) {
            return;
        }
        try {
            Context.authenticate(credentials[0], credentials[1]);
        } catch (ContextAuthenticationException ex) {
            // Ignore, the user might be logged in through another means already
        }
    }

    private LinkedHashMap<String, List<Concept>> buildChartModel()
            throws NoProfileException {
        LinkedHashMap<String, List<Concept>> charts = new LinkedHashMap<>();
        String chartName = null;
        ArrayList<Concept> concepts = null;
        // Get the first chart. Currently the "first chart" actually contains multiple charts, the
        // rest of the logic in this method is parsing those.
        Form form = ChartResource.getCharts(Context.getFormService()).get(0);
        // Get the structure for that chart.
        TreeMap<Integer, TreeSet<FormField>> formStructure = FormUtil.getFormStructure(form);
        TreeSet<FormField> rootNode = formStructure.get(0);
        for (FormField groupField : rootNode) {
            if (groupField.getField().getName().equals("[chart_divider]")) {
                // The first child of the [chart_divider] contains the chart name.
                chartName = formStructure.get(groupField.getId()).first().getField().getName();
                concepts = new ArrayList<>();
                // Chart divider has a subfield "notes" (see profile_apply). We work around that
                // here by skipping when we find a chart divider.
                // Chart dividers are a hack anyway.
                continue;
            }
            for (FormField fieldInGroup : formStructure.get(groupField.getId())) {
                if (chartName == null) {
                    throw new NoProfileException();
                }
                // TODO: if this is bottleneck, use a TreeSet. Suspect it won't be because it's only
                // called once / export
                Concept concept = fieldInGroup.getField().getConcept();
                if (!concepts.contains(concept)) {
                    concepts.add(concept);
                }
            }
            charts.put(chartName, concepts);
        }
        return charts;
    }

    private void generateExport(
            HttpServletRequest request, HttpServletResponse response, ModelMap model)
            throws NoProfileException {
        PatientService patientService = Context.getPatientService();
        ObsService obsService = Context.getObsService();
        OrderService orderService = Context.getOrderService();
        Concept orderExecutedConcept = DbUtil.getOrderExecutedConcept();

        List<Patient> patients = new ArrayList<>(patientService.getAllPatients());
        Collections.sort(patients, PATIENT_COMPARATOR);

        LinkedHashMap<String, List<Concept>> charts = buildChartModel();

        try {

            PrintWriter w = response.getWriter();
            writeHeader(w);

            for (Patient patient : patients) {
                w.write("<h2>" + patient.getPatientIdentifier("MSF") + ". "
                        + patient.getGivenName() + " " + patient.getFamilyName()
                        + "</h2><hr/>");

                List<Obs> observations = obsService.getObservations(
                        Collections.<Person>singletonList(patient), null, null,
                        null, null, null, null, null, null, null, null, false);
                if (observations.size() == 0) {
                    w.write("<b>No encounters for this patient</b>");
                    continue;
                }

                for (Map.Entry<String, List<Concept>> chart : charts.entrySet()) {
                    printPatientChart(observations, chart.getKey(), chart.getValue(), w);
                }

                printOrdersChart(obsService, orderService, orderExecutedConcept, w, patient);

            }
            writeFooter(w);

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void printOrdersChart(
            ObsService obsService, OrderService orderService, Concept orderExecutedConcept,
            PrintWriter w, Patient patient) {
        Calendar calendar = Calendar.getInstance();
        w.write("<h3>Treatment</h3>");

        Map<Order, String> orders = obtainOrders(orderService, patient);
        if (orders.size() == 0) {
            w.write("<h3>This patient has no treatments.</h3>");
            return;
        }
        List<Obs> orderExecutedObs = obsService.getObservations(
                Collections.<Person>singletonList(patient), null,
                Collections.singletonList(orderExecutedConcept),
                null, null, null, null, null, null, null, null, false);

        Pair<Date, Date> dates = getStartAndEndDateForOrders(
                orders.keySet(), orderExecutedObs);
        Date start = dates.getLeft();
        Date stop = dates.getRight();

        int day = 1;

        calendar.setTime(start);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        Date today = calendar.getTime();
        do {
            w.write("<table cellpadding=\"2\" cellspacing=\"0\" border=\"1\" width=\"100%\">\n"
                    + "\t<thead>\n"
                    + "\t\t<th width=\"20%\">&nbsp;</th>\n");
            calendar.setTime(today);
            for (int i = day; i < (day + 7); i++) {
                w.write("<th width=\"10%\">" + HEADER_DATE_FORMAT.format(calendar.getTime()) + "</th>");
                calendar.add(Calendar.DAY_OF_MONTH, 1);
            }
            w.write("\t</thead>\n"
                    + "\t<tbody>\n");

            for (Map.Entry<Order, String> entry : orders.entrySet()) {
                Order order = entry.getKey();
                String executedUuid = entry.getValue();
                w.write("<tr><td>");
                w.write(order.getInstructions());
                w.write(" " + formatStartAndEndDatesForOrder(order));
                w.write("</td>");

                calendar.setTime(today);
                for (int i = 1; i < 8; i++) {
                    Date dayStart = calendar.getTime();
                    Date dayEnd = OpenmrsUtil.getLastMomentOfDay(dayStart);
                    List<Obs> observations = obsService.getObservations(Collections.<Person>singletonList(patient), null,
                            Collections.singletonList(orderExecutedConcept), null, null, null, null, null, null, dayStart, dayEnd,
                            false);
                    String value = "&nbsp;";
                    if (!observations.isEmpty()) {
                        int numGiven = 0;
                        for (Obs observation : observations) {
                            if (observation.getOrder().getUuid().equals(executedUuid)) {
                                numGiven++;
                            }
                        }
                        if (numGiven > 0) {
                            value = String.valueOf(numGiven);
                        }
                    }
                    w.write("<td>" + value + "</td>");
                    calendar.add(Calendar.DAY_OF_MONTH, 1);
                }
                w.write("</tr>");
            }

            w.write("\t</tbody>\n"
                    + "</table>\n");

            day += 7;
            calendar.setTime(today);
            calendar.add(Calendar.DAY_OF_MONTH, 7);
            today = calendar.getTime();
        } while (today.before(stop) || today.equals(stop));
    }

    /**
     * Because we abstract editable orders as chains of orders (see {@link OrderResource}), the
     * printed patient charts will show all orders, and all edits, unless we do some filtering.
     * This method returns a Map of the lastest revised orders to their root Orders' UUIDs. The root
     * Order's UUID is used as a stable identifier for the Order Execution concept.
     */
    private Map<Order, String> obtainOrders(OrderService orderService, Patient patient) {
        List<Order> orders = orderService.getAllOrdersByPatient(patient);
        HashMap<Order, String> filteredOrders = new HashMap<>();
        for (Order order : orders) {
            Order newest = OrderResource.getLatestVersion(order);
            filteredOrders.put(newest, OrderResource.getEarliestVersion(order).getUuid());
        }
        return filteredOrders;
    }

    private String formatStartAndEndDatesForOrder(Order order) {
        if (order.getScheduledDate() == null) {
            // Shouldn't occur, but fail safe.
            return "";
        }
        String startDateString = ORDER_DATE_FORMAT.format(order.getScheduledDate());
        String endDateString = order.getAutoExpireDate() == null
                        ? "*"
                        : ORDER_DATE_FORMAT.format(order.getAutoExpireDate());
        return String.format("(%s - %s)", startDateString, endDateString);
    }

    private Pair<Date, Date> getStartAndEndDateForOrders(
            Iterable<Order> orders,
            Iterable<Obs> orderExecutedObs) {
        Date start = null;
        Date stop = null;
        for (Order order : orders) {
            if (start == null || order.getScheduledDate().before(start)) {
                start = order.getScheduledDate();
            }
            if (order.getAutoExpireDate() != null) {
                if (stop == null || order.getAutoExpireDate().after(stop)) {
                    stop = order.getAutoExpireDate();
                }
            }
        }

        for (Obs obs : orderExecutedObs) {
            Date obsTime = obs.getObsDatetime();
            if (start == null || obsTime.before(start)) {
                start = obsTime;
            }
            if (stop == null || obsTime.after(stop)) {
                stop = obsTime;
            }
        }

        // This shouldn't ever occur, but fail gracefully.
        if (start == null) {
            start = new Date();
        }
        // If all orders are unlimited orders, this will print into forever. we fix that by ending
        // printing at the start date.
        if (stop == null) {
            stop = start;
        }
        return Pair.of(start, stop);
    }

    private void printPatientChart(
            List<Obs> observations, String chartName,
            List<Concept> questionConcepts, PrintWriter w) {
        w.write("<h3>" + chartName + "</h3>");
        // NOTE: this assumes that the list of observations will have a length of at least 1.
        assert observations.size() > 0;
        final Date earliest = observations.get(observations.size() - 1).getObsDatetime();
        final Date latest = observations.get(0).getObsDatetime();
        // We already know the earliest date and the latest date, so we can start splitting up into
        // weeks.
        final Date earliestDay = getStartOfDay(earliest);
        HashMap<Concept, Map<Date, List<Obs>>> obsByConcept =
                constructObsMap(observations, earliestDay);

        // Each loop iteration is a week.
        Date weekStart = earliestDay;
        Calendar weekCal = Calendar.getInstance();
        weekCal.setTime(earliestDay);
        while (weekStart.before(latest)) {
            writeWeek(w, weekStart, questionConcepts, obsByConcept);
            weekCal.add(Calendar.DATE, 7);
            weekStart = weekCal.getTime();
        }
    }

    private Date getStartOfDay(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    /**
     * Construct a mapping of Concept --> Dates --> List of observations.
     * <p>
     * This method capitalizes on the fact that observations come back from MySQL sorted to make
     * processing much faster than calling into the DB multiple times.
     * @param observations all observations for the patient, sorted by observation time DESC. This
     *                     is the format that OpenMRS supplies by default.
     * @param earliestDay can be determined from the last observation in the set, but provided here
     *                    to avoid computing it multiple times.
     */
    private HashMap<Concept, Map<Date, List<Obs>>> constructObsMap(
            List<Obs> observations, Date earliestDay) {
        // Map of a concept, to a list of days. Each day contains a list of obs.
        HashMap<Concept, Map<Date, List<Obs>>> obsByConcept = new HashMap<>();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(earliestDay);
        Date start = earliestDay;
        calendar.add(Calendar.DATE, 1);
        Date end = calendar.getTime();
        for (int i = observations.size() - 1; i >= 0; i--) {
            Obs obs = observations.get(i);

            // Try to retrieve, add if it doesn't exist yet.
            Map<Date, List<Obs>> conceptObs = obsByConcept.get(obs.getConcept());
            if (conceptObs == null) {
                conceptObs = new HashMap<>();
                obsByConcept.put(obs.getConcept(), conceptObs);
            }

            // Use ! (obs before end) because this puts obs on the correct day if it occurred at
            // midnight.
            while (!obs.getObsDatetime().before(end)) {
                // Move to the next day.
                start = end;
                calendar.add(Calendar.DATE, 1);
                end = calendar.getTime();
            }

            // Try to retrieve, add if it doesn't exist yet.
            List<Obs> obsSet = conceptObs.get(start);
            if (obsSet == null) {
                obsSet = new ArrayList<>();
                conceptObs.put(start, obsSet);
            }

            obsSet.add(obs);
        }
        return obsByConcept;
    }

    private void writeWeek(PrintWriter w, Date startDate, List<Concept> questionConcepts,
                           HashMap<Concept, Map<Date, List<Obs>>> obsByConcept) {
        // Write header row for this week.
        w.write("<table cellpadding=\"2\" cellspacing=\"0\" border=\"1\" width=\"100%\">\n"
                + "\t<thead>\n"
                + "\t\t<th width=\"20%\">&nbsp;</th>\n");
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(startDate);
        for (int i = 0; i < 7; i++) {
            w.write("<th width=\"10%\">"
                    + HEADER_DATE_FORMAT.format(calendar.getTime()) + "</th>");
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
        w.write("\t</thead>\n"
                + "\t<tbody>\n");

        for (Concept concept : questionConcepts) {
            Map<Date, List<Obs>> datesForConcept = obsByConcept.get(concept);
            if (datesForConcept == null) {
                // this concept wasn't used, skip.
                continue;
            }

            w.write("<tr><td>");
            w.write(NAMER.getClientName(concept));
            w.write("</td>");

            calendar.setTime(startDate);
            Date today = startDate;
            for (int i = 0; i < 7; i++) {
                ArrayList<String> values = new ArrayList<>();
                List<Obs> dayOfObservations = datesForConcept.get(today);
                if (dayOfObservations != null) {
                    for (Obs obs : dayOfObservations) {
                        values.add(VisitObsValue.visit(obs, STRING_VISITOR));
                    }
                }
                w.write("<td>" + StringUtils.join(values, ", ") + "</td>");
                calendar.add(Calendar.DATE, 1);
                today = calendar.getTime();
            }
            w.write("</tr>");
        }

        w.write("\t</tbody>\n"
                + "</table>\n");
    }

    private void writeHeader(PrintWriter w) {
        w.write("<!doctype html>\n"
                + "<html>\n"
                + "<head>\n"
                + "  <meta charset=\"utf-8\">\n"
                + "  <title>Patient Charts</title>\n"
                + "  <style type=\"text/css\">\n"
                + "    table { margin-bottom: 22pt; }\n"
                + "    table, tr, thead, tbody {\n"
                + "        page-break-inside: avoid;\n"
                + "    }\n"
                + "    h3 { page-break-after: avoid; }\n"
                + "    h2 { margin 10dp; page-break-before: always;}\n"
                + "    h2:first-child { page-break-before: auto; }\n"
                + "    td { page-break-inside:avoid; }\n"
                + "    thead {background-color:#D5D5D5;}\n"
                + "    body { font-size: 10pt; }\n"
//                + "tr    \n"
//                + "{ \n"
//                + "  display: table-row-group;\n"
//                + "  page-break-inside:avoid; \n"
//                + "  page-break-after:auto;\n"
//                + "}\n"
                //+ "@media print {\n"
                //+ "   thead {display: table-header-group;}\n"
                //+ "}"
                + "  </style>\n"
                + "</head>\n"
                + "<body>");
    }

    private void writeFooter(PrintWriter w) {
        w.write("</body>\n"
                + "</html>");
    }

    private static class NoProfileException extends Exception {
    }
}
