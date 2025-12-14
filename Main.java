import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Single-file Java application:
 * - User registration (ID), stores users in users.csv
 * - Workout selection (preset days + sub-exercises)
 * - Choose sets & reps per exercise (user input)
 * - Calories estimation (simple MET-like heuristic)
 * - File I/O: users.csv and workouts.csv
 * - Exception handling
 * - Collections: Map for users, List for workouts
 * - Searching / sorting workout history
 * - Recommendations of lift weight based on age/height/weight and previous progress
 *
 * Compile: javac Main.java
 * Run:     java Main
 *
 * Requires JDK 8+
 */
public class Main {

    /* ------------------------------
       Domain classes
       ------------------------------ */

    static class User {
        private final String id;
        private String name;
        private String gender;
        private int age;
        private double heightCm;
        private double weightKg;
        private LocalDateTime createdAt;

        public User(String name, String gender, int age, double heightCm, double weightKg) {
            this.id = UUID.randomUUID().toString();
            this.name = name;
            this.gender = gender;
            this.age = age;
            this.heightCm = heightCm;
            this.weightKg = weightKg;
            this.createdAt = LocalDateTime.now();
        }

        // For loading from CSV
        public User(String id, String name, String gender, int age, double heightCm, double weightKg, LocalDateTime createdAt) {
            this.id = id; this.name = name; this.gender = gender; this.age = age; this.heightCm = heightCm; this.weightKg = weightKg; this.createdAt = createdAt;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getGender() { return gender; }
        public int getAge() { return age; }
        public double getHeightCm() { return heightCm; }
        public double getWeightKg() { return weightKg; }
        public void setWeightKg(double w) { this.weightKg = w; }
        public void setHeightCm(double h) { this.heightCm = h; }
        public void setAge(int a) { this.age = a; }

        public String toCsvRow() {
            return escapeCsv(id) + "," + escapeCsv(name) + "," + escapeCsv(gender) + "," + age + "," + heightCm + "," + weightKg + "," + createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }

        public static User fromCsvRow(String row) {
            // id,name,gender,age,height,weight,createdAt
            String[] cols = splitCsv(row);
            if (cols.length < 7) return null;
            try {
                String id = cols[0];
                String name = cols[1];
                String gender = cols[2];
                int age = Integer.parseInt(cols[3]);
                double height = Double.parseDouble(cols[4]);
                double weight = Double.parseDouble(cols[5]);
                LocalDateTime createdAt = LocalDateTime.parse(cols[6], DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return new User(id, name, gender, age, height, weight, createdAt);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return String.format("%s (%s) ID:%s Age:%d Height:%.1fcm Weight:%.1fkg", name, gender, id.substring(0,8), age, heightCm, weightKg);
        }
    }

    static class Exercise {
        private final String name;
        private final boolean isCardio;
        private final double met; // approximate MET value or intensity proxy
        private final String group; // chest/back/legs etc.

        public Exercise(String name, String group, boolean cardio, double met) {
            this.name = name;
            this.group = group;
            this.isCardio = cardio;
            this.met = met;
        }

        public String getName() { return name; }
        public boolean isCardio() { return isCardio; }
        public double getMet() { return met; }
        public String getGroup() { return group; }

        @Override
        public String toString() {
            return name + (isCardio ? " (cardio)" : "");
        }
    }

    static class WorkoutDay {
        private final String name;
        private final List<Exercise> exercises;

        public WorkoutDay(String name) {
            this.name = name;
            this.exercises = new ArrayList<>();
        }

        public void add(Exercise e) { exercises.add(e); }
        public String getName() { return name; }
        public List<Exercise> getExercises() { return exercises; }
    }

    static class WorkoutRecord {
        private final String recordId;
        private final String userId;
        private final String userName;
        private final String workoutDay;
        private final List<String> exerciseSummaries; // "ExerciseName: sets x reps @ suggestedLoadKg => calories"
        private final double totalCalories;
        private final LocalDateTime timestamp;
        private final double userWeightAtTime;

        public WorkoutRecord(String userId, String userName, String workoutDay, List<String> exerciseSummaries, double totalCalories, double userWeightAtTime) {
            this.recordId = UUID.randomUUID().toString();
            this.userId = userId;
            this.userName = userName;
            this.workoutDay = workoutDay;
            this.exerciseSummaries = new ArrayList<>(exerciseSummaries);
            this.totalCalories = totalCalories;
            this.timestamp = LocalDateTime.now();
            this.userWeightAtTime = userWeightAtTime;
        }

        public String toCsvRow() {
            // recordId,userId,userName,workoutDay,ex1|ex2|...,totalCalories,userWeight,timestamp
            String joined = String.join("||", exerciseSummaries);
            return escapeCsv(recordId) + "," + escapeCsv(userId) + "," + escapeCsv(userName) + "," + escapeCsv(workoutDay) + "," +
                    escapeCsv(joined) + "," + String.format(Locale.US,"%.2f", totalCalories) + "," + String.format(Locale.US,"%.2f", userWeightAtTime) + "," +
                    timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }

        public static WorkoutRecord fromCsvRow(String row) {
            String[] c = splitCsv(row);
            if (c.length < 8) return null;
            try {
                // we will reconstruct minimal fields
                String recordId = c[0];
                String userId = c[1];
                String userName = c[2];
                String workoutDay = c[3];
                String joined = c[4];
                double totalCalories = Double.parseDouble(c[5]);
                double userWeight = Double.parseDouble(c[6]);
                LocalDateTime ts = LocalDateTime.parse(c[7], DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                List<String> exs = new ArrayList<>();
                if (!joined.isEmpty()) {
                    exs = Arrays.asList(joined.split("\\|\\|"));
                }
                WorkoutRecord wr = new WorkoutRecord(userId, userName, workoutDay, exs, totalCalories, userWeight);
                // cannot set recordId or timestamp since constructor generates new; but it's okay for display purposes
                return wr;
            } catch (Exception e) {
                return null;
            }
        }

        public String brief() {
            return String.format("%s | %s | %.2f kcal | %s", userName, workoutDay, totalCalories, timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        }

        public String full() {
            StringBuilder sb = new StringBuilder();
            sb.append("User: ").append(userName).append(" (").append(userId).append(")\n");
            sb.append("Workout Day: ").append(workoutDay).append("\n");
            sb.append("When: ").append(timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n");
            sb.append("Weight at time: ").append(String.format("%.2f", userWeightAtTime)).append(" kg\n");
            sb.append("Exercises:\n");
            for (String s : exerciseSummaries) sb.append(" - ").append(s).append("\n");
            sb.append("Total calories: ").append(String.format("%.2f", totalCalories)).append("\n");
            return sb.toString();
        }

        public String getUserId() { return userId; }
        public double getTotalCalories() { return totalCalories; }
    }

    /* ------------------------------
       Manager for presets and file IO
       ------------------------------ */

    static class WorkoutManager {
        private final Map<String, User> users = new LinkedHashMap<>(); // id -> user
        private final List<WorkoutRecord> records = new ArrayList<>();

        private final Path usersFile = Paths.get("users.csv");
        private final Path workoutsFile = Paths.get("workouts.csv");

        // Presets
        private final List<WorkoutDay> presets = new ArrayList<>();

        public WorkoutManager() {
            loadPresets();
            loadUsers();
            loadRecords();
        }

        private void loadPresets() {
            WorkoutDay chest = new WorkoutDay("Chest & Triceps Day");
            chest.add(new Exercise("Incline Bench Press (Barbell/Dumbbell)", "chest", false, 6.5));
            chest.add(new Exercise("Flat Bench Press", "chest", false, 6.3));
            chest.add(new Exercise("Dumbbell Fly (Flat/Incline)", "chest", false, 5.2));
            chest.add(new Exercise("Triceps Pushdown", "triceps", false, 4.8));
            presets.add(chest);

            WorkoutDay back = new WorkoutDay("Back & Biceps Day");
            back.add(new Exercise("Pull-Ups / Assisted Pull-Ups", "back", false, 7.0));
            back.add(new Exercise("Barbell Row", "back", false, 6.2));
            back.add(new Exercise("Seated Cable Row", "back", false, 6.0));
            back.add(new Exercise("Barbell Curl", "biceps", false, 4.3));
            presets.add(back);

            WorkoutDay legs = new WorkoutDay("Legs & Shoulders Day");
            legs.add(new Exercise("Squats (Back/Front)", "legs", false, 8.0));
            legs.add(new Exercise("Leg Press", "legs", false, 6.5));
            legs.add(new Exercise("Romanian Deadlift", "legs", false, 7.0));
            legs.add(new Exercise("Overhead Press", "shoulders", false, 6.0));
            presets.add(legs);

            WorkoutDay core = new WorkoutDay("Abs & Core Day");
            core.add(new Exercise("Plank (seconds-based)", "core", false, 3.5));
            core.add(new Exercise("Hanging Leg Raise", "core", false, 4.2));
            core.add(new Exercise("Russian Twist", "core", false, 4.0));
            core.add(new Exercise("Jump Rope (cardio)", "cardio", true, 10.0));
            presets.add(core);

            WorkoutDay cardio = new WorkoutDay("Cardio / Mixed");
            cardio.add(new Exercise("Running (moderate)", "cardio", true, 9.8));
            cardio.add(new Exercise("Cycling (moderate)", "cardio", true, 7.5));
            presets.add(cardio);
        }

        /* ---------- File IO: users ---------- */
        private void loadUsers() {
            if (Files.exists(usersFile)) {
                try (BufferedReader br = Files.newBufferedReader(usersFile, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        User u = User.fromCsvRow(line);
                        if (u != null) users.put(u.getId(), u);
                    }
                } catch (IOException e) {
                    System.err.println("Failed to load users: " + e.getMessage());
                }
            } else {
                // create file with header
                try {
                    Files.write(usersFile, ("id,name,gender,age,heightCm,weightKg,createdAt\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
                } catch (IOException ignored) {}
            }
        }

        public synchronized void saveUser(User u) {
            users.put(u.getId(), u);
            try {
                Files.write(usersFile, (u.toCsvRow() + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("Failed to save user: " + e.getMessage());
            }
        }

        public User findUserById(String id) {
            return users.get(id);
        }

        public User findUserByName(String name) {
            for (User u : users.values()) {
                if (u.getName().equalsIgnoreCase(name)) return u;
            }
            return null;
        }

        public Collection<User> allUsers() { return users.values(); }

        /* ---------- File IO: workout records ---------- */
        private void loadRecords() {
            if (Files.exists(workoutsFile)) {
                try (BufferedReader br = Files.newBufferedReader(workoutsFile, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        WorkoutRecord r = WorkoutRecord.fromCsvRow(line);
                        if (r != null) records.add(r);
                    }
                } catch (IOException e) {
                    System.err.println("Failed to load records: " + e.getMessage());
                }
            } else {
                try {
                    Files.write(workoutsFile, ("recordId,userId,userName,workoutDay,exercises,totalCalories,userWeight,timestamp\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
                } catch (IOException ignored) {}
            }
        }

        public synchronized void appendRecord(WorkoutRecord r) {
            records.add(r);
            try {
                Files.write(workoutsFile, (r.toCsvRow() + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("Failed to write record: " + e.getMessage());
            }
        }

        public List<WorkoutRecord> getRecordsForUser(String userId) {
            List<WorkoutRecord> out = new ArrayList<>();
            for (WorkoutRecord r : records) if (r.getUserId().equals(userId)) out.add(r);
            return out;
        }

        public List<WorkoutRecord> allRecords() { return new ArrayList<>(records); }

        public List<WorkoutDay> getPresets() { return presets; }
    }

    /* ------------------------------
       Utilities & heuristics
       ------------------------------ */

    // simple calories estimate:
    // cardio: calories = met * weightKg * hours
    // strength: estimate from sets*reps*time per rep & 6 MET baseline
    static class Estimator {
        public static double estimateCardioCalories(double met, double weightKg, int minutes) {
            if (minutes <= 0) return 0;
            double hours = minutes / 60.0;
            return met * weightKg * hours;
        }

        public static double estimateStrengthCalories(double weightKg, int sets, int reps) {
            // assume ~3 seconds per rep -> time per set = reps*3 sec
            double totalSeconds = sets * reps * 3.0;
            double minutes = Math.max(1.0, totalSeconds / 60.0);
            double met = 6.0; // approximate MET for weight training
            return met * weightKg * (minutes / 60.0);
        }

        // Suggest a starting target load (kg) for an exercise, using user weight, age, and exercise group.
        // This is heuristic only: e.g. for compound lifts we suggest higher percent of bodyweight.
        public static double suggestLoadKg(User u, Exercise e) {
            double base = u.getWeightKg() * 0.5; // baseline 50% bodyweight
            String g = e.getGroup().toLowerCase();
            double factor = 0.5;
            if (g.contains("legs")) factor = 1.0;        // legs can handle more
            else if (g.contains("chest") || g.contains("back")) factor = 0.7;
            else if (g.contains("shoulder") || g.contains("triceps") || g.contains("biceps")) factor = 0.35;
            else if (g.contains("core")) factor = 0.15;
            else if (g.contains("cardio")) factor = 0.1;

            // age adjustment: older -> slightly lower starting load
            double ageAdj = 1.0;
            if (u.getAge() >= 60) ageAdj = 0.75;
            else if (u.getAge() >= 45) ageAdj = 0.85;
            else if (u.getAge() >= 30) ageAdj = 0.95;

            double suggested = base * factor * ageAdj;
            // round to nearest 2.5
            return Math.round(suggested / 2.5) * 2.5;
        }

        // Suggest progression based on weight change
        public static String progressionAdvice(User u, List<WorkoutRecord> history) {
            if (history.isEmpty()) return "No history yet — start with conservative loads and track sets/reps.";
            // find last recorded weight
            double lastWeight = -1;
            for (int i = history.size()-1; i>=0; i--) {
                WorkoutRecord r = history.get(i);
                if (r != null) {
                    lastWeight = r.userWeightAtTime;
                    break;
                }
            }
            if (lastWeight <= 0) return "No previous weight recorded in history.";
            double diff = u.getWeightKg() - lastWeight;
            if (diff >= 2.0) return "You gained " + String.format("%.1f", diff) + "kg since last workout — consider increasing loads gradually (~2.5-5% per week).";
            if (diff <= -2.0) return "You lost " + String.format("%.1f", -diff) + "kg — reduce loads slightly and focus on technique and nutrition.";
            return "Bodyweight stable — aim to progressively overload (add 1–2.5 kg to compound lifts every 1–2 weeks if form is good).";
        }
    }

    /* ------------------------------
       Main interactive program
       ------------------------------ */

    private static final Scanner SC = new Scanner(System.in);
    private static final WorkoutManager manager = new WorkoutManager();

    public static void main(String[] args) {
        System.out.println("=== Calories Burned & Workout Planner ===");
        boolean running = true;

        while (running) {
            try {
                System.out.println("\nMain Menu:");
                System.out.println("1) Register new user");
                System.out.println("2) Login as existing user (by ID or name)");
                System.out.println("3) List all users");
                System.out.println("4) View all workout records (admin)");
                System.out.println("5) Exit");
                System.out.print("Select option: ");
                String opt = SC.nextLine().trim();
                switch (opt) {
                    case "1": registerUser(); break;
                    case "2": loginUser(); break;
                    case "3": listUsers(); break;
                    case "4": viewAllRecords(); break;
                    case "5": running = false; break;
                    default: System.out.println("Invalid option.");
                }
            } catch (Exception e) {
                System.err.println("Unhandled error: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }

        System.out.println("Goodbye!");
    }

    /* ---------- User flows ---------- */

    private static void registerUser() {
        try {
            System.out.print("Enter name: ");
            String name = SC.nextLine().trim();
            if (name.isEmpty()) { System.out.println("Name required."); return; }
            System.out.print("Enter gender (M/F/Other): ");
            String gender = SC.nextLine().trim();
            System.out.print("Enter age (years): ");
            int age = Integer.parseInt(SC.nextLine().trim());
            System.out.print("Enter height (cm): ");
            double height = Double.parseDouble(SC.nextLine().trim());
            System.out.print("Enter weight (kg): ");
            double weight = Double.parseDouble(SC.nextLine().trim());

            User u = new User(name, gender, age, height, weight);
            manager.saveUser(u);
            System.out.println("User created: " + u);
            System.out.println("Your user ID (save this): " + u.getId());
        } catch (NumberFormatException e) {
            System.out.println("Invalid numeric input. Registration cancelled.");
        } catch (Exception e) {
            System.out.println("Error registering user: " + e.getMessage());
        }
    }

    private static void loginUser() {
        try {
            System.out.print("Enter user ID or name: ");
            String q = SC.nextLine().trim();
            User u = manager.findUserById(q);
            if (u == null) u = manager.findUserByName(q);
            if (u == null) { System.out.println("User not found."); return; }

            System.out.println("Welcome " + u.getName() + "!");
            userMenu(u);
        } catch (Exception e) {
            System.out.println("Login error: " + e.getMessage());
        }
    }

    private static void listUsers() {
        Collection<User> users = manager.allUsers();
        if (users.isEmpty()) { System.out.println("No users registered."); return; }
        System.out.println("Registered users:");
        for (User u : users) {
            System.out.println(" - " + u.toString());
        }
    }

    private static void viewAllRecords() {
        List<WorkoutRecord> recs = manager.allRecords();
        if (recs.isEmpty()) { System.out.println("No workout records."); return; }
        System.out.println("All workout records (latest first):");
        // sort by timestamp descending if possible by using brief() order (we can't access timestamp easily from WorkoutRecord)
        // We'll just print in insertion order which mirrors saved file order
        Collections.reverse(recs);
        for (WorkoutRecord r : recs) {
            System.out.println(" - " + r.brief());
        }
    }

    /* ---------- per-user menu ---------- */

    private static void userMenu(User u) {
        boolean back = false;
        while (!back) {
            try {
                System.out.println("\nUser Menu for " + u.getName() + " (" + u.getId().substring(0,8) + ")");
                System.out.println("1) Start a workout (pick preset or custom)");
                System.out.println("2) View my workout history");
                System.out.println("3) Update my weight/height/age");
                System.out.println("4) Get recommendations & progression advice");
                System.out.println("5) Back to main menu");
                System.out.print("Choice: ");
                String c = SC.nextLine().trim();
                switch (c) {
                    case "1": startWorkoutFlow(u); break;
                    case "2": viewUserHistory(u); break;
                    case "3": updateUserProfile(u); break;
                    case "4": showRecommendations(u); break;
                    case "5": back = true; break;
                    default: System.out.println("Invalid choice.");
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    private static void startWorkoutFlow(User u) {
        try {
            System.out.println("\nChoose workout day preset (or 0 for custom):");
            List<WorkoutDay> presets = manager.getPresets();
            for (int i = 0; i < presets.size(); i++) {
                System.out.printf("%d) %s%n", i+1, presets.get(i).getName());
            }
            System.out.print("Choice: ");
            int choice = Integer.parseInt(SC.nextLine().trim());
            WorkoutDay selected;
            if (choice >= 1 && choice <= presets.size()) {
                selected = presets.get(choice - 1);
            } else {
                // custom creation
                System.out.print("Enter custom workout day name: ");
                String dayName = SC.nextLine().trim();
                selected = new WorkoutDay(dayName.isEmpty() ? "Custom" : dayName);
                System.out.println("Add exercises (type 'done' to finish). Format: name|group|cardio(true/false)|metValue");
                while (true) {
                    System.out.print("Exercise: ");
                    String line = SC.nextLine().trim();
                    if (line.equalsIgnoreCase("done")) break;
                    String[] parts = line.split("\\|");
                    if (parts.length < 4) { System.out.println("Invalid format, try again."); continue; }
                    String ename = parts[0].trim();
                    String group = parts[1].trim();
                    boolean cardio = Boolean.parseBoolean(parts[2].trim());
                    double met = 0;
                    try { met = Double.parseDouble(parts[3].trim()); } catch (Exception ex) { met = cardio ? 7.0 : 5.0; }
                    selected.add(new Exercise(ename, group, cardio, met));
                }
            }

            System.out.println("\nStarting workout: " + selected.getName());
            List<String> exSummaries = new ArrayList<>();
            double totalCalories = 0.0;

            for (Exercise e : selected.getExercises()) {
                System.out.println("\nExercise: " + e.getName());
                // allow choose sub-exercise variant or choose to skip
                if (!e.isCardio()) {
                    System.out.print("Enter sets : ");
                    int sets = Integer.parseInt(SC.nextLine().trim());
                    System.out.print("Enter reps per set : ");
                    int reps = Integer.parseInt(SC.nextLine().trim());
                    double suggestedKg = Estimator.suggestLoadKg(u, e);
                    System.out.println("Suggested starting load: " + String.format("%.1f", suggestedKg) + " kg (heuristic)");
                    System.out.print("Enter load used (kg) or press Enter to use suggested: ");
                    String loadInput = SC.nextLine().trim();
                    double usedKg = suggestedKg;
                    if (!loadInput.isEmpty()) {
                        try { usedKg = Double.parseDouble(loadInput); } catch (Exception ex) { usedKg = suggestedKg; }
                    }
                    double cal = Estimator.estimateStrengthCalories(u.getWeightKg(), sets, reps);
                    totalCalories += cal;
                    exSummaries.add(String.format("%s: %dx%d @ %.1fkg => %.2f kcal", e.getName(), sets, reps, usedKg, cal));
                    System.out.println("Calories estimated: " + String.format("%.2f", cal));
                } else {
                    // cardio: ask duration
                    System.out.print("Enter duration in minutes (int): ");
                    int mins = Integer.parseInt(SC.nextLine().trim());
                    double cal = Estimator.estimateCardioCalories(e.getMet(), u.getWeightKg(), mins);
                    totalCalories += cal;
                    exSummaries.add(String.format("%s: %d min => %.2f kcal", e.getName(), mins, cal));
                    System.out.println("Calories estimated: " + String.format("%.2f", cal));
                }
            }

            WorkoutRecord rec = new WorkoutRecord(u.getId(), u.getName(), selected.getName(), exSummaries, totalCalories, u.getWeightKg());
            manager.appendRecord(rec);
            System.out.println("\nWorkout saved! Total estimated calories: " + String.format("%.2f", totalCalories));

            // After finishing workout, offer to update weight if changed
            System.out.print("Update weight now? (y/n): ");
            String upd = SC.nextLine().trim();
            if (upd.equalsIgnoreCase("y")) {
                System.out.print("Enter new weight (kg): ");
                double newW = Double.parseDouble(SC.nextLine().trim());
                u.setWeightKg(newW);
                // Also persist updated user with appended row (note: this simple app appends another user-row to users.csv)
                manager.saveUser(u);
                System.out.println("Weight updated and saved.");
            }

        } catch (NumberFormatException nfe) {
            System.out.println("Input must be numeric where requested. Workout cancelled.");
        } catch (Exception e) {
            System.out.println("Failed to start workout: " + e.getMessage());
        }
    }

    private static void viewUserHistory(User u) {
        List<WorkoutRecord> recs = manager.getRecordsForUser(u.getId());
        if (recs.isEmpty()) {
            System.out.println("No workout history for this user.");
            return;
        }
        // Offer sorting options
        System.out.println("History options:");
        System.out.println("1) Show latest first");
        System.out.println("2) Show earliest first");
        System.out.println("3) Sort by calories (desc)");
        System.out.print("Choice: ");
        String c = SC.nextLine().trim();
        if (c.equals("1")) Collections.reverse(recs);
        else if (c.equals("3")) recs.sort((a,b) -> Double.compare(b.getTotalCalories(), a.getTotalCalories()));

        System.out.println("\n--- Workout History ---");
        int idx = 1;
        for (WorkoutRecord r : recs) {
            System.out.println("\n[" + (idx++) + "] " + r.brief());
            // show brief details
            System.out.println(r.full());
        }
    }

    private static void updateUserProfile(User u) {
        try {
            System.out.println("Current profile: " + u);
            System.out.print("New weight (kg) or press Enter to skip: ");
            String sW = SC.nextLine().trim();
            if (!sW.isEmpty()) {
                double w = Double.parseDouble(sW);
                u.setWeightKg(w);
            }
            System.out.print("New height (cm) or press Enter to skip: ");
            String sH = SC.nextLine().trim();
            if (!sH.isEmpty()) {
                double h = Double.parseDouble(sH);
                u.setHeightCm(h);
            }
            System.out.print("New age or press Enter to skip: ");
            String sA = SC.nextLine().trim();
            if (!sA.isEmpty()) {
                int a = Integer.parseInt(sA);
                u.setAge(a);
            }
            // persist update by appending user row (simple approach)
            manager.saveUser(u);
            System.out.println("Profile updated and saved.");
        } catch (NumberFormatException e) {
            System.out.println("Invalid numeric input. Update aborted.");
        } catch (Exception e) {
            System.out.println("Error updating profile: " + e.getMessage());
        }
    }

    private static void showRecommendations(User u) {
        System.out.println("\nRecommendations for " + u.getName());
        System.out.println("Suggested loads for preset exercises:");
        for (WorkoutDay wd : manager.getPresets()) {
            System.out.println("\n" + wd.getName() + ":");
            for (Exercise e : wd.getExercises()) {
                double sug = Estimator.suggestLoadKg(u, e);
                System.out.printf(" - %s : suggested start load %.1f kg%n", e.getName(), sug);
            }
        }
        List<WorkoutRecord> recs = manager.getRecordsForUser(u.getId());
        System.out.println("\nProgression advice: " + Estimator.progressionAdvice(u, recs));
    }

    /* ------------------------------
       Small helpers & CSV utilities
       ------------------------------ */

    private static String escapeCsv(String s) {
        if (s == null) return "";
        boolean need = s.contains(",") || s.contains("\"") || s.contains("\n");
        String out = s.replace("\"", "\"\"");
        if (need) out = "\"" + out + "\"";
        return out;
    }

    private static String[] splitCsv(String line) {
        if (line == null) return new String[0];
        List<String> cols = new ArrayList<>();
        boolean inQuote = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuote = !inQuote;
                continue;
            }
            if (ch == ',' && !inQuote) {
                cols.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cols.add(cur.toString());
        return cols.toArray(new String[0]);
    }
}