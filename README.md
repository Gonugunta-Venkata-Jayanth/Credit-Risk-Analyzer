# Credit Risk Analyzer (Enhanced)

This enhanced project includes CLI options, weight overrides, and configurable thresholds.

Files included:
- CreditRiskAnalyzer.java  : Main Java source file (enhanced with CLI flags)
- synthetic_cibil_scores.csv: Dataset (your uploaded file)
- run.sh                   : Helper script to compile and run with examples
- README.md                : This file
- .gitignore               : Basic git ignore

New CLI options:
--input <file>       Input CSV file (default: synthetic_cibil_scores.csv)
--output <file>      Output CSV file (default: credit_risk_report.csv)
--top <N>            Print top N highest-risk applicants (default: 10)
--weights w1,...,w6  Override weights (order: CIBIL,Payment,CreditUtil,CreditAge,DTI,HardInq). They will be normalized.
--thresholds med,low Override thresholds for mapping composite score -> risk. Example: --thresholds 55,75

Example run:
javac CreditRiskAnalyzer.java
java CreditRiskAnalyzer --input synthetic_cibil_scores.csv --output credit_risk_report.csv --top 20 --weights 0.4,0.25,0.12,0.1,0.08,0.05 --thresholds 52,72

Notes:
- The CSV reader is simple (splits on commas). If your data contains commas within fields, pre-process or improve the parser.
- Adjust weights and thresholds to tune the model for your use case.