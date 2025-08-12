#!/bin/bash
# Helper script. Usage: ./run.sh
set -e
javac CreditRiskAnalyzer.java
echo "Compiled."
# Example run with default args
java CreditRiskAnalyzer --input synthetic_cibil_scores.csv --output credit_risk_report.csv --top 10
echo "Output written to credit_risk_report.csv"