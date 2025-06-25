package com.example.grapheditor;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import com.google.gson.*;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

public class GraphEditor extends Application {

    // ==== Core data structures ====

    static class Node {
        double x, y;
        String id;
        String name;
        static int counter = 0;

        Node(double x, double y) {
            this.x = x;
            this.y = y;
            this.id = "N" + (counter++);
            this.name = this.id;
        }
    }

    static class Edge {
        Node a, b;
        double weight;

        Edge(Node a, Node b) {
            this.a = a;
            this.b = b;
            this.weight = Math.hypot(a.x - b.x, a.y - b.y);
        }
    }

    // ==== Fields ====

    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();

    // Camera state (world offset and zoom)
    private double camX = 0, camY = 0;
    private double camVX = 0, camVY = 0;  // velocity for "jelly" effect
    private double zoom = 1.0;

    private static final double CAMERA_DAMPING = 0.85;
    private static final double CAMERA_ACCEL = 0.8;

    // UI
    private Canvas canvas;
    private GraphicsContext gc;
    private BorderPane root;
    private VBox menu;

    // Interaction state
    private Node selectedNode = null;
    private final Set<Node> multiConnectSet = new LinkedHashSet<>();
    private boolean multiConnectMode = false;
    private Node pathStartNode = null;
    private Node pathEndNode = null;
    private List<Node> lastPath = Collections.emptyList();

    // Mouse drag for camera movement
    private double lastMouseX, lastMouseY;
    private boolean draggingCanvas = false;

    // Gson instance for JSON serialization
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void start(Stage stage) {
        root = new BorderPane();

        canvas = new Canvas(1000, 700);
        gc = canvas.getGraphicsContext2D();

        menu = createMenu();

        ToggleButton toggleEditorBtn = new ToggleButton("Toggle Editor");
        toggleEditorBtn.setSelected(true);
        toggleEditorBtn.setOnAction(e -> {
            boolean on = toggleEditorBtn.isSelected();
            menu.setVisible(on);
            multiConnectMode = false;
            multiConnectSet.clear();
        });

        HBox topBar = new HBox(10, toggleEditorBtn);
        topBar.setPadding(new Insets(5));
        topBar.setStyle("-fx-background-color: #ddd;");
        root.setTop(topBar);

        root.setLeft(menu);
        root.setCenter(canvas);

        setupCanvasHandlers(stage);

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setTitle("Graph Editor Advanced");
        stage.show();

        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                updateCamera();
                render();
            }
        };
        timer.start();
    }

    private VBox createMenu() {
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(10));
        vbox.setPrefWidth(260);
        vbox.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #ccc;");

        Label title = new Label("Editor Menu");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16;");

        Button btnClear = new Button("Clear All");
        btnClear.setOnAction(e -> {
            nodes.clear();
            edges.clear();
            selectedNode = null;
            multiConnectSet.clear();
            multiConnectMode = false;
            pathStartNode = null;
            pathEndNode = null;
            lastPath = Collections.emptyList();
        });

        Button btnMultiConnectToggle = new Button("Start Multi-Connect");
        btnMultiConnectToggle.setOnAction(e -> {
            multiConnectMode = !multiConnectMode;
            if (!multiConnectMode) {
                multiConnectSet.clear();
                btnMultiConnectToggle.setText("Start Multi-Connect");
            } else {
                btnMultiConnectToggle.setText("Finish Multi-Connect");
            }
        });

        Button btnConnectSelected = new Button("Connect Selected Pair");
        btnConnectSelected.setOnAction(e -> {
            if (selectedNode != null && pathStartNode != null && selectedNode != pathStartNode) {
                connectNodes(pathStartNode, selectedNode);
                pathStartNode = null;
            }
        });

        Button btnSetPathStart = new Button("Set Path Start");
        btnSetPathStart.setOnAction(e -> {
            if (selectedNode != null) {
                pathStartNode = selectedNode;
            }
        });

        Button btnSetPathEnd = new Button("Set Path End");
        btnSetPathEnd.setOnAction(e -> {
            if (selectedNode != null) {
                pathEndNode = selectedNode;
            }
        });

        Button btnFindPath = new Button("Find Path");
        btnFindPath.setOnAction(e -> {
            if (pathStartNode != null && pathEndNode != null) {
                lastPath = dijkstraPath(pathStartNode, pathEndNode);
            }
        });

        Button btnSave = new Button("Save Graph");
        btnSave.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Save Graph JSON");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
            File file = fileChooser.showSaveDialog(root.getScene().getWindow());
            if (file != null) {
                saveGraphToFile(file);
            }
        });

        Button btnLoad = new Button("Load Graph");
        btnLoad.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Load Graph JSON");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
            File file = fileChooser.showOpenDialog(root.getScene().getWindow());
            if (file != null) {
                loadGraphFromFile(file);
            }
        });

        Label info = new Label("""
                Instructions:
                - Left click: Add/select node
                - Right click drag: Move camera
                - Scroll: Zoom
                - Multi-Connect: select nodes in sequence to connect
                - Path: Set start/end nodes, then find path""");

        vbox.getChildren().addAll(
                title,
                btnClear,
                btnMultiConnectToggle,
                new Separator(),
                btnSetPathStart,
                btnSetPathEnd,
                btnFindPath,
                new Separator(),
                btnSave,
                btnLoad,
                new Separator(),
                info
        );
        return vbox;
    }

    private void connectNodes(Node a, Node b) {
        if (a == b) return;
        if (!edgeExists(a, b)) {
            edges.add(new Edge(a, b));
        }
    }

    private boolean edgeExists(Node a, Node b) {
        for (Edge edge : edges) {
            if ((edge.a == a && edge.b == b) || (edge.a == b && edge.b == a)) {
                return true;
            }
        }
        return false;
    }

    private void setupCanvasHandlers(Stage stage) {
        canvas.setOnMousePressed(e -> {
            lastMouseX = e.getX();
            lastMouseY = e.getY();

            if (e.getButton() == MouseButton.PRIMARY) {
                Node clicked = findNodeAtScreen(e.getX(), e.getY());
                if (clicked != null) {
                    selectNode(clicked);
                } else {
                    double wx = screenToWorldX(e.getX());
                    double wy = screenToWorldY(e.getY());
                    Node newNode = new Node(wx, wy);
                    nodes.add(newNode);
                    selectNode(newNode);
                }
            } else if (e.getButton() == MouseButton.SECONDARY) {
                draggingCanvas = true;
            }
        });

        canvas.setOnMouseDragged(e -> {
            double dx = e.getX() - lastMouseX;
            double dy = e.getY() - lastMouseY;

            if (draggingCanvas) {
                camVX += dx / zoom * CAMERA_ACCEL;
                camVY += dy / zoom * CAMERA_ACCEL;
            } else if (selectedNode != null && e.getButton() == MouseButton.PRIMARY && !multiConnectMode) {
                double wx = screenToWorldX(e.getX());
                double wy = screenToWorldY(e.getY());
                selectedNode.x = wx;
                selectedNode.y = wy;
            }

            lastMouseX = e.getX();
            lastMouseY = e.getY();
        });

        canvas.setOnMouseReleased(e -> draggingCanvas = false);

        canvas.setOnScroll((ScrollEvent e) -> {
            if (e.getDeltaY() > 0) zoom *= 1.1;
            else zoom /= 1.1;
            zoom = clamp(zoom, 0.2, 5);

            double mx = e.getX();
            double my = e.getY();

            double wx1 = screenToWorldX(mx);
            double wy1 = screenToWorldY(my);

            camX += (wx1 - screenToWorldX(mx)) * zoom;
            camY += (wy1 - screenToWorldY(my)) * zoom;
        });
    }

    private void selectNode(Node newNode) {
        selectedNode = newNode;
        if (multiConnectMode) {
            if (!multiConnectSet.contains(newNode)) {
                multiConnectSet.add(newNode);
                if (multiConnectSet.size() > 1) {
                    Node[] arr = multiConnectSet.toArray(new Node[0]);
                    connectNodes(arr[arr.length - 2], arr[arr.length - 1]);
                }
            }
        }
    }

    private double clamp(double val, double min, double max) {
        if (val < min) return min;
        return Math.min(val, max);
    }

    private double screenToWorldX(double sx) {
        return (sx - canvas.getWidth() / 2) / zoom - camX;
    }

    private double screenToWorldY(double sy) {
        return (sy - canvas.getHeight() / 2) / zoom - camY;
    }

    private double worldToScreenX(double wx) {
        return (wx + camX) * zoom + canvas.getWidth() / 2;
    }

    private double worldToScreenY(double wy) {
        return (wy + camY) * zoom + canvas.getHeight() / 2;
    }

    private Node findNodeAtScreen(double sx, double sy) {
        final double radius = 10;
        for (Node node : nodes) {
            double nx = worldToScreenX(node.x);
            double ny = worldToScreenY(node.y);
            if (Math.hypot(nx - sx, ny - sy) < radius) {
                return node;
            }
        }
        return null;
    }

    private void updateCamera() {
        camX += camVX;
        camY += camVY;
        camVX *= CAMERA_DAMPING;
        camVY *= CAMERA_DAMPING;
    }

    private void render() {
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        gc.setFill(Color.web("#fefefe"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        gc.setLineCap(StrokeLineCap.ROUND);

        // Draw edges
        gc.setStroke(Color.GRAY);
        gc.setLineWidth(2);
        for (Edge edge : edges) {
            double x1 = worldToScreenX(edge.a.x);
            double y1 = worldToScreenY(edge.a.y);
            double x2 = worldToScreenX(edge.b.x);
            double y2 = worldToScreenY(edge.b.y);
            gc.strokeLine(x1, y1, x2, y2);
        }

        // Highlight path edges
        if (lastPath.size() > 1) {
            gc.setStroke(Color.RED);
            gc.setLineWidth(4);
            for (int i = 0; i < lastPath.size() - 1; i++) {
                Node a = lastPath.get(i);
                Node b = lastPath.get(i + 1);
                gc.strokeLine(worldToScreenX(a.x), worldToScreenY(a.y),
                        worldToScreenX(b.x), worldToScreenY(b.y));
            }
        }

        // Draw nodes
        for (Node node : nodes) {
            double x = worldToScreenX(node.x);
            double y = worldToScreenY(node.y);
            boolean isSelected = node == selectedNode;
            boolean isInMultiConnect = multiConnectSet.contains(node);
            boolean isPathStart = node == pathStartNode;
            boolean isPathEnd = node == pathEndNode;

            if (isSelected) gc.setFill(Color.ORANGE);
            else if (isPathStart) gc.setFill(Color.GREEN);
            else if (isPathEnd) gc.setFill(Color.RED);
            else if (isInMultiConnect) gc.setFill(Color.CORNFLOWERBLUE);
            else gc.setFill(Color.DARKCYAN);

            gc.fillOval(x - 8, y - 8, 16, 16);

            gc.setStroke(Color.BLACK);
            gc.strokeOval(x - 8, y - 8, 16, 16);

            gc.setFill(Color.BLACK);
            gc.fillText(node.name, x + 10, y - 10);
        }

        // Info text
        gc.setFill(Color.BLACK);
        gc.fillText("Nodes: " + nodes.size() + " | Edges: " + edges.size(), 10, canvas.getHeight() - 10);

        if (selectedNode != null) {
            gc.fillText("Selected: " + selectedNode.name, 10, canvas.getHeight() - 25);
        }
        if (!multiConnectSet.isEmpty()) {
            gc.fillText("Multi-Connect nodes: " + multiConnectSet.size(), 10, canvas.getHeight() - 40);
        }
        if (pathStartNode != null) {
            gc.fillText("Path start: " + pathStartNode.name, 10, canvas.getHeight() - 55);
        }
        if (pathEndNode != null) {
            gc.fillText("Path end: " + pathEndNode.name, 10, canvas.getHeight() - 70);
        }
    }

    // ========== Dijkstra's Algorithm for shortest path ==========

    private List<Node> dijkstraPath(Node start, Node goal) {
        Map<Node, Double> dist = new HashMap<>();
        Map<Node, Node> prev = new HashMap<>();
        PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingDouble(dist::get));

        for (Node n : nodes) dist.put(n, Double.POSITIVE_INFINITY);
        dist.put(start, 0.0);

        queue.add(start);

        while (!queue.isEmpty()) {
            Node u = queue.poll();

            if (u == goal) break;

            for (Node v : neighbors(u)) {
                double alt = dist.get(u) + distBetween(u, v);
                if (alt < dist.get(v)) {
                    dist.put(v, alt);
                    prev.put(v, u);
                    queue.remove(v);
                    queue.add(v);
                }
            }
        }

        // Reconstruct path
        List<Node> path = new LinkedList<>();
        Node cur = goal;
        while (cur != null) {
            path.addFirst(cur);
            cur = prev.get(cur);
        }
        if (path.size() == 1 && path.getFirst() != start) return Collections.emptyList();
        return path;
    }

    private List<Node> neighbors(Node node) {
        List<Node> result = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge.a == node) result.add(edge.b);
            else if (edge.b == node) result.add(edge.a);
        }
        return result;
    }

    private double distBetween(Node a, Node b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    // ========== JSON Save/Load ==========

    static class GraphData {
        List<NodeData> nodes = new ArrayList<>();
        List<EdgeData> edges = new ArrayList<>();
    }

    static class NodeData {
        String id;
        String name;
        double x, y;
    }

    static class EdgeData {
        String aId, bId;
    }

    private void saveGraphToFile(File file) {
        try {
            if (!file.getName().toLowerCase().endsWith(".json")) {
                file = new File(file.getAbsolutePath() + ".json");
            }

            GraphData gd = new GraphData();
            for (Node n : nodes) {
                NodeData nd = new NodeData();
                nd.id = n.id;
                nd.name = n.name;
                nd.x = n.x;
                nd.y = n.y;
                gd.nodes.add(nd);
            }
            for (Edge e : edges) {
                EdgeData ed = new EdgeData();
                ed.aId = e.a.id;
                ed.bId = e.b.id;
                gd.edges.add(ed);
            }

            try (Writer writer = new FileWriter(file)) {
                gson.toJson(gd, writer);
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }


    private void loadGraphFromFile(File file) {
        try {
            String json = Files.readString(file.toPath());
            GraphData gd = gson.fromJson(json, GraphData.class);

            // Clear current graph
            nodes.clear();
            edges.clear();
            selectedNode = null;
            multiConnectSet.clear();
            multiConnectMode = false;
            pathStartNode = null;
            pathEndNode = null;
            lastPath = Collections.emptyList();

            // Rebuild nodes map by id
            Map<String, Node> idMap = new HashMap<>();
            for (NodeData nd : gd.nodes) {
                Node n = new Node(nd.x, nd.y);
                n.id = nd.id;
                n.name = nd.name;
                idMap.put(n.id, n);
                nodes.add(n);
            }
            // Fix counter for new nodes
            Node.counter = nodes.size();

            // Rebuild edges
            for (EdgeData ed : gd.edges) {
                Node a = idMap.get(ed.aId);
                Node b = idMap.get(ed.bId);
                if (a != null && b != null) {
                    edges.add(new Edge(a, b));
                }
            }

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
