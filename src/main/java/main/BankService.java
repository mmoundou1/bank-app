package main;

public class BankService {

    private BankAccountsDb bankAccountsDb;

    public BankService() {}

    public BankService(BankAccountsDb bankAccountsDb) {
        this.bankAccountsDb = bankAccountsDb;

    }

    public double getBalance(int id) {

        Account temp = bankAccountsDb.findAcc(id);

        return temp.getBalance();

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
