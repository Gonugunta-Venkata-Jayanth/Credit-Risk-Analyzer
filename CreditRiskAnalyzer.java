/*
Enhanced CreditRiskAnalyzer.java
Features added:
 - CLI flags: --input <file>, --output <file>, --top <N>, --weights w1,w2,..., --thresholds med,low
 - Weights can override default weights (order: CIBIL,Payment,CreditUtil,CreditAge,DTI,HardInq)
 - Thresholds override risk mapping (two numbers, in this order: medium_threshold,low_threshold,
   e.g. --thresholds 50,70 means >=70 LOW, >=50 MEDIUM, otherwise HIGH)
 - Writes output CSV and prints top-N risky applicants and summary
 - Usage examples in README.md
*/

import java.io.*;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.stream.*;

public class CreditRiskAnalyzer {

    enum RiskLevel { LOW, MEDIUM, HIGH }

    static class Applicant {
        int id; // row id (starting from 1)
        double paymentHistory;          // 0-100
        double creditUtilization;       // ratio 0-1 (lower is better)
        double creditAge;               // years (higher is better)
        int numberOfAccounts;           // integer
        int hardInquiries;              // integer (lower is better)
        double debtToIncomeRatio;       // ratio 0-1 (lower is better)
        double cibilScore;              // 300-900

        double compositeScore;          // 0-100 (higher is better)
        RiskLevel risk;

        Applicant(int id) { this.id = id; }

        String toCsvRow() {
            DecimalFormat df = new DecimalFormat("#.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
            return String.join(",",
                String.valueOf(id),
                df.format(paymentHistory),
                df.format(creditUtilization),
                df.format(creditAge),
                String.valueOf(numberOfAccounts),
                String.valueOf(hardInquiries),
                df.format(debtToIncomeRatio),
                df.format(cibilScore),
                df.format(compositeScore),
                risk.name()
            );
        }
    }

    // Default weights (sum close to 1.0)
    static double W_CIBIL = 0.40;
    static double W_PAYMENT = 0.20;
    static double W_CREDIT_UTIL = 0.15;
    static double W_CREDIT_AGE = 0.10;
    static double W_DEBT_TO_INCOME = 0.08;
    static double W_HARD_INQ = 0.07; // small penalty weight

    // Default thresholds for composite score -> risk mapping (MEDIUM threshold, LOW threshold)
    static double THRESH_MED = 50.0;
    static double THRESH_LOW = 70.0;

    public static void main(String[] args) {
        // Defaults
        String input = "synthetic_cibil_scores.csv";
        String output = "credit_risk_report.csv";
        int topN = 10;

        // Parse CLI args simply
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--input") && i + 1 < args.length) {
                input = args[++i];
            } else if (a.equals("--output") && i + 1 < args.length) {
                output = args[++i];
            } else if (a.equals("--top") && i + 1 < args.length) {
                try { topN = Integer.parseInt(args[++i]); } catch (NumberFormatException e) {}
            } else if (a.equals("--weights") && i + 1 < args.length) {
                String warg = args[++i];
                parseAndSetWeights(warg);
            } else if (a.equals("--thresholds") && i + 1 < args.length) {
                String targ = args[++i];
                parseAndSetThresholds(targ);
            } else if (a.equals("--help") || a.equals("-h")) {
                printUsageAndExit();
            }
        }

        List<Applicant> applicants = readCsv(input);
        if (applicants == null) return;

        // compute composite scores and risk
        for (Applicant a : applicants) {
            a.compositeScore = computeCompositeScore(a);
            a.risk = mapRisk(a.compositeScore);
        }

        // write output csv
        boolean ok = writeOutputCsv(output, applicants);
        if (!ok) return;

        // show summary
        printSummary(applicants);

        // show top N highest risk (i.e., lowest composite scores)
        System.out.println("\nTop " + topN + " Highest-Risk Applicants (lowest composite score):");
        applicants.stream()
            .sorted(Comparator.comparingDouble(a -> a.compositeScore))
            .limit(topN)
            .forEach(a -> System.out.printf(
                "ID:%d Score:%.2f Risk:%s CIBIL:%.2f Payment:%.1f CU:%.2f DTI:%.2f\n",
                a.id, a.compositeScore, a.risk.name(), a.cibilScore, a.paymentHistory, a.creditUtilization, a.debtToIncomeRatio
            ));

        System.out.println("\nReport written to: " + output);
    }

    static void printUsageAndExit() {
        System.out.println("Usage: java CreditRiskAnalyzer [--input file] [--output file] [--top N] [--weights w1,w2,w3,w4,w5,w6] [--thresholds med,low]");
        System.out.println("Weights order: CIBIL,Payment,CreditUtil,CreditAge,DTI,HardInq. Example: --weights 0.4,0.2,0.15,0.1,0.08,0.07");
        System.out.println("Thresholds: medium_threshold,low_threshold (e.g. --thresholds 55,75)"); 
        System.exit(0);
    }

    static void parseAndSetWeights(String warg) {
        String[] parts = warg.split(",");
        if (parts.length != 6) {
            System.err.println("Invalid weights. Expected 6 comma-separated values."); return;
        }
        try {
            double c = Double.parseDouble(parts[0]);
            double p = Double.parseDouble(parts[1]);
            double cu = Double.parseDouble(parts[2]);
            double ca = Double.parseDouble(parts[3]);
            double dti = Double.parseDouble(parts[4]);
            double hi = Double.parseDouble(parts[5]);
            double sum = c + p + cu + ca + dti + hi;
            if (Math.abs(sum - 1.0) > 0.001) {
                System.out.printf("Warning: weights sum to %.4f (expected ~1.0). They will be normalized.\n", sum);
            }
            // Normalize
            W_CIBIL = c / sum;
            W_PAYMENT = p / sum;
            W_CREDIT_UTIL = cu / sum;
            W_CREDIT_AGE = ca / sum;
            W_DEBT_TO_INCOME = dti / sum;
            W_HARD_INQ = hi / sum;
            System.out.println("Weights set. New values:");
            System.out.printf("CIBIL=%.3f, Payment=%.3f, CreditUtil=%.3f, CreditAge=%.3f, DTI=%.3f, HardInq=%.3f\n",
                W_CIBIL, W_PAYMENT, W_CREDIT_UTIL, W_CREDIT_AGE, W_DEBT_TO_INCOME, W_HARD_INQ);
        } catch (NumberFormatException e) {
            System.err.println("Invalid number in weights: " + e.getMessage());
        }
    }

    static void parseAndSetThresholds(String targ) {
        String[] parts = targ.split(",");
        if (parts.length != 2) {
            System.err.println("Invalid thresholds. Expected 2 comma-separated values: med,low"); return;
        }
        try {
            double med = Double.parseDouble(parts[0]);
            double low = Double.parseDouble(parts[1]);
            if (med >= low) {
                System.err.println("Invalid thresholds: medium threshold must be less than low threshold (e.g., 50,70)"); return;
            }
            THRESH_MED = med;
            THRESH_LOW = low;
            System.out.printf("Thresholds set. medium=%.2f, low=%.2f\n", THRESH_MED, THRESH_LOW);
        } catch (NumberFormatException e) {
            System.err.println("Invalid number in thresholds: " + e.getMessage());
        }
    }

    static List<Applicant> readCsv(String path) {
        List<Applicant> list = new ArrayList<>();
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            System.err.println("Input file not found: " + path);
            return null;
        }

        try (BufferedReader br = Files.newBufferedReader(p)) {
            String header = br.readLine();
            if (header == null) {
                System.err.println("CSV is empty");
                return null;
            }
            // We expect header with 7 fields; but we'll be tolerant and parse by position
            String line;
            int row = 0;
            while ((line = br.readLine()) != null) {
                row++;
                if (line.trim().isEmpty()) continue;
                List<String> fields = parseCsvLine(line);
                // A quoted field may span several physical lines; keep reading until quotes balance
                while (fields == null) {
                    String next = br.readLine();
                    if (next == null) break;
                    line = line + "\n" + next;
                    fields = parseCsvLine(line);
                }
                if (fields == null) {
                    System.err.println("Skipping line #" + row + " with an unterminated quoted field");
                    continue;
                }
                String[] cols = fields.toArray(new String[0]);
                if (cols.length < 7) {
                    // try to be tolerant for whitespace
                    System.err.println("Skipping malformed line #" + row + ": " + line);
                    continue;
                }
                Applicant a = new Applicant(row);
                try {
                    a.paymentHistory = parseDoubleSafe(cols[0]);
                    a.creditUtilization = parseDoubleSafe(cols[1]);
                    a.creditAge = parseDoubleSafe(cols[2]);
                    a.numberOfAccounts = (int) parseDoubleSafe(cols[3]);
                    a.hardInquiries = (int) parseDoubleSafe(cols[4]);
                    a.debtToIncomeRatio = parseDoubleSafe(cols[5]);
                    a.cibilScore = parseDoubleSafe(cols[6]);
                    list.add(a);
                } catch (NumberFormatException nfe) {
                    System.err.println("Skipping line due to number parse error #" + row + ": " + nfe.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            return null;
        }
        System.out.println("Loaded " + list.size() + " applicants from " + path);
        return list;
    }

    /**
     * Splits one CSV record (RFC 4180 style): fields may be wrapped in double quotes,
     * quoted fields may contain commas or line breaks, and "" inside quotes is a literal quote.
     * Returns null if the record ends inside an open quoted field.
     */
    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (inQuotes) return null;
        fields.add(cur.toString());
        return fields;
    }

    static double parseDoubleSafe(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0.0;
        return Double.parseDouble(s);
    }

    // Compute a composite score [0..100] combining normalized components.
    static double computeCompositeScore(Applicant a) {
        // Normalize CIBIL (300-900) -> 0-100
        double cibilNorm = clamp((a.cibilScore - 300.0) / 600.0 * 100.0, 0.0, 100.0);

        // Payment history is already 0-100 (higher better)
        double payNorm = clamp(a.paymentHistory, 0.0, 100.0);

        // Credit utilization: lower is better. We'll invert and map 0-1 to 0-100 (cap at 200% utilization -> minimum)
        double cu = clamp(a.creditUtilization, 0.0, 2.0); // 2.0 means 200% utilization
        double cuNorm = (1.0 - (cu / 2.0)) * 100.0;         // 0 utilization -> 100, 2.0 -> 0

        // Credit age: assume 0-30 years maps to 0-100
        double ca = clamp(a.creditAge, 0.0, 30.0);
        double caNorm = (ca / 30.0) * 100.0;

        // Debt to income: lower is better. Map 0-2.0 to 0-100
        double dti = clamp(a.debtToIncomeRatio, 0.0, 2.0);
        double dtiNorm = (1.0 - (dti / 2.0)) * 100.0;

        // Hard inquiries: fewer is better. We'll map 0-10 inquiries -> 100..0 (cap at 10)
        double hi = clamp(a.hardInquiries, 0.0, 10.0);
        double hiNorm = (1.0 - (hi / 10.0)) * 100.0;

        // Weighted sum
        double composite = W_CIBIL * cibilNorm
                         + W_PAYMENT * payNorm
                         + W_CREDIT_UTIL * cuNorm
                         + W_CREDIT_AGE * caNorm
                         + W_DEBT_TO_INCOME * dtiNorm
                         + W_HARD_INQ * hiNorm;

        // Ensure 0-100
        return clamp(composite, 0.0, 100.0);
    }

    static RiskLevel mapRisk(double compositeScore) {
        if (compositeScore >= THRESH_LOW) return RiskLevel.LOW;
        if (compositeScore >= THRESH_MED) return RiskLevel.MEDIUM;
        return RiskLevel.HIGH;
    }

    static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    static boolean writeOutputCsv(String outPath, List<Applicant> applicants) {
        Path p = Paths.get(outPath);
        try (BufferedWriter bw = Files.newBufferedWriter(p)) {
            // header
            bw.write(String.join("," ,
                "ID",
                "Payment_History",
                "Credit_Utilization",
                "Credit_Age",
                "Number_of_Accounts",
                "Hard_Inquiries",
                "Debt_to_Income_Ratio",
                "CIBIL_Score",
                "Composite_Score",
                "Risk_Level"
            ));
            bw.newLine();

            for (Applicant a : applicants) {
                bw.write(a.toCsvRow());
                bw.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error writing output file: " + e.getMessage());
            return false;
        }
        return true;
    }

    static void printSummary(List<Applicant> applicants) {
        long low = applicants.stream().filter(a -> a.risk == RiskLevel.LOW).count();
        long med = applicants.stream().filter(a -> a.risk == RiskLevel.MEDIUM).count();
        long high = applicants.stream().filter(a -> a.risk == RiskLevel.HIGH).count();
        System.out.println("\nSummary:");
        System.out.println("Total applicants: " + applicants.size());
        System.out.println("Low risk: " + low);
        System.out.println("Medium risk: " + med);
        System.out.println("High risk: " + high);

        double avgScore = applicants.stream().mapToDouble(a -> a.compositeScore).average().orElse(0.0);
        System.out.printf("Average composite score: %.2f\n", avgScore);
    }
}
