package org.example;

import java.util.ArrayList;
import java.util.List;

public class QuotesList
{
    List<Quote> quotes = new ArrayList<>();

    public QuotesList()
    {
        Quote quote1 = new Quote("Hi im quote 1", "Puleesha", 2025);
        Quote quote2 = new Quote("papapap", "Alex", 2018);
        Quote quote3 = new Quote("lollol ⛴️", "Bob", 2016);
        Quote quote4 = new Quote("hehe", "Charlie", 2020);

        quotes.addAll(List.of(quote1, quote2, quote3, quote4));
    }

    public List<Quote> getQuotes()
    {
        return quotes;
    }
}
