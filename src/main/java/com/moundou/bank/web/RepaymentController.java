package com.moundou.bank.web;

import com.moundou.bank.identity.SignedInMember;
import com.moundou.bank.ledger.Amounts;
import com.moundou.bank.ledger.LedgerTransaction;
import com.moundou.bank.ledger.RepaymentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class RepaymentController {

    private final RepaymentService repaymentService;

    RepaymentController(RepaymentService repaymentService) {
        this.repaymentService = repaymentService;
    }

    @GetMapping("/repayments")
    public String repayments(@AuthenticationPrincipal SignedInMember me, Model model) {

        List<LedgerTransaction> repayments = repaymentService.outstandingLoansOwedBy(me.memberId()).orElseThrow();
        model.addAttribute("repayments", repayments);

        return "repayments";
    }

    @GetMapping("/repayments/new?loan={id}")
    public String repaymentViewFor(@AuthenticationPrincipal SignedInMember me,
                                                            @RequestParam(required = false) UUID loanId, Model model) {

        LedgerTransaction transaction = repaymentService.viewTransaction(me.memberId(), loanId).orElseThrow();
        model.addAttribute("transaction", transaction);

        return "repaymentView";
    }

    @PostMapping("/repayments")
    public String repaymentRequest(@AuthenticationPrincipal SignedInMember me,
                                                    RepaymentService.RepaymentRequest repaymentRequest, Model model) {


        return "";
    }

}