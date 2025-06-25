module com.example.grapheditor {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.google.gson;


    opens com.example.grapheditor to javafx.fxml, com.google.gson;
    exports com.example.grapheditor;
}