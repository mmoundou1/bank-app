package main;

import java.util.LinkedList;

public class Utility {

    private RepList repList;
    private LinkedList<Rep> innerRepList;
    private BankAccountsDb bankAccountsDb;

    public Utility(RepList repList, BankAccountsDb bankAccountsDb) {
        this.repList = repList;
        this.bankAccountsDb = bankAccountsDb;
    }


    public Utility() {

        /*innerRepList = new LinkedList<>();
        repList = new RepList();

        innerRepList.add(new Rep("Matt", 111));
        innerRepList.add(new Rep("Stephanie", 222));
        innerRepList.add(new Rep("Jeremy", 333));

        repList.setRepList(innerRepList);*/

    }

    public void deposit(int id, double amount) {
        bankAccountsDb.findAcc(id).setBalance(amount);
    }

    public boolean withdraw(int id, double amount, BankAccountsDb bankAccountsDb) {
        if (amount > bankAccountsDb.findAcc(id).getBalance())
            return false;
        bankAccountsDb.findAcc(id).setBalance(bankAccountsDb.findAcc(id).getBalance() - amount);

        return true;
    }

}
