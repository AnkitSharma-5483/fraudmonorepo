minimal api design will be followed as the controller system will be followed in springboot apps

app.MapPost("/data-handler", (TransactionData transactionData, FraudDetectionHandler handler) 
    => handler.ProcessTransaction(transactionData));