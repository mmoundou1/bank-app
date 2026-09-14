package main;

import java.util.LinkedList;

public class Utility {

    private RepList repList;
    private LinkedList<Rep> innerRepList;
    private AccountsDb accountsDb;

    public Utility(RepList repList, AccountsDb accountsDb) {
        this.repList = repList;
        this.accountsDb = accountsDb;
    }


    public Utility() {

        innerRepList = new LinkedList<>();
        repList = new RepList();

        innerRepList.add(new Rep("Matt", 111));
        innerRepList.add(new Rep("Stephanie", 222));
        innerRepList.add(new Rep("Jeremy", 333));

        repList.setRepList(innerRepList);

    }

    public Rep findRep() {

        return repList.getAvailableRep();

    }

    public void deposit(int id, double amount) {
        accountsDb.findAcc(id).setBalance(amount);
    }

}
