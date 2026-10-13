package com.jotheca.seed;

import com.jotheca.authors.model.AuthorProfile;
import com.jotheca.authors.repo.AuthorProfileRepository;
import com.jotheca.billing.model.LibrarySubscription;
import com.jotheca.billing.model.SubscriptionPlan;
import com.jotheca.billing.repo.LibrarySubscriptionRepository;
import com.jotheca.billing.repo.SubscriptionPlanRepository;
import com.jotheca.catalog.model.Book;
import com.jotheca.catalog.model.BookCopy;
import com.jotheca.catalog.model.Branch;
import com.jotheca.catalog.model.Category;
import com.jotheca.catalog.model.CopyStatus;
import com.jotheca.catalog.model.ShelfLocation;
import com.jotheca.catalog.repo.BookCopyRepository;
import com.jotheca.catalog.repo.BookRepository;
import com.jotheca.catalog.repo.BranchRepository;
import com.jotheca.catalog.repo.CategoryRepository;
import com.jotheca.catalog.repo.ShelfLocationRepository;
import com.jotheca.common.tenant.CodeGenerator;
import com.jotheca.circulation.model.AppNotification;
import com.jotheca.circulation.model.Loan;
import com.jotheca.circulation.repo.LoanRepository;
import com.jotheca.circulation.repo.NotificationRepository;
import com.jotheca.identity.model.GlobalRole;
import com.jotheca.identity.model.User;
import com.jotheca.identity.repo.UserRepository;
import com.jotheca.membership.model.Invitation;
import com.jotheca.membership.model.LibraryRole;
import com.jotheca.membership.model.Membership;
import com.jotheca.membership.model.MembershipStatus;
import com.jotheca.membership.model.MembershipType;
import com.jotheca.membership.repo.InvitationRepository;
import com.jotheca.membership.repo.MembershipRepository;
import com.jotheca.tenancy.model.JoinPolicy;
import com.jotheca.tenancy.model.Library;
import com.jotheca.tenancy.model.LibraryStatus;
import com.jotheca.tenancy.model.LibraryType;
import com.jotheca.tenancy.repo.LibraryRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dev-profile demo fixtures: one SUPER_ADMIN, one library staff pair, one patron, and three
 * Accra-area libraries with different join policies (PUBLIC / APPROVAL_REQUIRED / INVITATION_ONLY)
 * so every Phase-1/2 flow is clickable in the live demo. Never runs outside the dev profile.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    public static final String PENTECOST_UID = "LIB-PU2026";
    public static final String ACCRA_UID = "LIB-AC2026";
    public static final String ASHESI_UID = "LIB-AU2026";
    public static final String AUTHOR_UID = "LIB-AW2026";
    public static final String ASHESI_INVITE_CODE = "ASH-2026";

    private final UserRepository users;
    private final LibraryRepository libraries;
    private final MembershipRepository memberships;
    private final InvitationRepository invitations;
    private final SubscriptionPlanRepository plans;
    private final LibrarySubscriptionRepository subscriptions;
    private final CategoryRepository categories;
    private final BranchRepository branches;
    private final ShelfLocationRepository shelfLocations;
    private final BookRepository books;
    private final BookCopyRepository copies;
    private final LoanRepository loans;
    private final NotificationRepository notifications;
    private final AuthorProfileRepository authorProfiles;
    private final PasswordEncoder encoder;
    private final String superAdminPassword;

    public DevDataSeeder(UserRepository users, LibraryRepository libraries, MembershipRepository memberships,
                         InvitationRepository invitations, SubscriptionPlanRepository plans,
                         LibrarySubscriptionRepository subscriptions, CategoryRepository categories,
                         BranchRepository branches, ShelfLocationRepository shelfLocations,
                         BookRepository books, BookCopyRepository copies, LoanRepository loans,
                         NotificationRepository notifications, AuthorProfileRepository authorProfiles,
                         PasswordEncoder encoder,
                         @Value("${SEED_SUPER_ADMIN_PASSWORD:Admin12345!}") String superAdminPassword) {
        this.users = users;
        this.libraries = libraries;
        this.memberships = memberships;
        this.invitations = invitations;
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.categories = categories;
        this.branches = branches;
        this.shelfLocations = shelfLocations;
        this.books = books;
        this.copies = copies;
        this.loans = loans;
        this.notifications = notifications;
        this.authorProfiles = authorProfiles;
        this.encoder = encoder;
        this.superAdminPassword = superAdminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        user("admin@jotheca.app", superAdminPassword, "Platform Owner", GlobalRole.SUPER_ADMIN);
        User libAdmin = user("libadmin@jotheca.app", "Library12345!", "Ama Mensah", GlobalRole.USER);
        User staff = user("staff@jotheca.app", "Library12345!", "Kwame Osei", GlobalRole.USER);
        User patron = user("patron@jotheca.app", "Patron12345!", "Efua Boateng", GlobalRole.USER);
        User accraAdmin = user("accraadmin@jotheca.app", "Library12345!", "Yaw Darko", GlobalRole.USER);

        Library pentecost = library(PENTECOST_UID, "pentecost", "Pentecost University Library",
                LibraryType.UNIVERSITY, JoinPolicy.APPROVAL_REQUIRED, "Sowutuom, Accra",
                "University library serving Pentecost University students and faculty.",
                "Mon–Fri 8:00–20:00 · Sat 9:00–14:00");
        Library accra = library(ACCRA_UID, "accra-central", "Accra Central Public Library",
                LibraryType.PUBLIC, JoinPolicy.PUBLIC, "Accra",
                "Ghana's flagship public lending library — open membership for everyone.",
                "Daily 9:00–17:00");
        Library ashesi = library(ASHESI_UID, "ashesi", "Ashesi University Library",
                LibraryType.UNIVERSITY, JoinPolicy.INVITATION_ONLY, "Berekuso",
                "Ashesi University campus library. Membership via institutional invitation.",
                "24/7 reading room");

        memberships.save(member(pentecost, libAdmin, LibraryRole.LIBRARY_ADMIN, MembershipType.STAFF,
                MembershipStatus.APPROVED, "MBR-PU0001"));
        memberships.save(member(pentecost, staff, LibraryRole.LIBRARIAN, MembershipType.STAFF,
                MembershipStatus.APPROVED, "MBR-PU0002"));
        memberships.save(member(accra, patron, LibraryRole.PATRON, MembershipType.PUBLIC_MEMBER,
                MembershipStatus.APPROVED, "MBR-AC0001"));
        memberships.save(member(accra, accraAdmin, LibraryRole.LIBRARY_ADMIN, MembershipType.STAFF,
                MembershipStatus.APPROVED, "MBR-AC0002"));
        memberships.save(member(pentecost, patron, LibraryRole.PATRON, MembershipType.STUDENT,
                MembershipStatus.PENDING, null));
        pentecost.setMemberCount(2);
        accra.setMemberCount(2);
        accra.setAllowAnonymousReviews(true);   // demo: public library tolerates anonymous reviews
        libraries.save(pentecost);
        libraries.save(accra);

        attachPlan(pentecost, "PROFESSIONAL");
        attachPlan(accra, "STARTER");
        attachPlan(ashesi, "ENTERPRISE");

        Invitation invite = new Invitation();
        invite.setTenantId(ashesi.getId());
        invite.setSlug("ashesi");
        invite.setCode(ASHESI_INVITE_CODE);
        invite.setMembershipType(MembershipType.STUDENT);
        invite.setAutoApprove(true);
        invitations.save(invite);

        seedCatalog(accra, "AC");
        seedCatalog(pentecost, "PU");
        seedCirculation(accra, patron);
        seedAuthorLibrary();

        log.info("[seed] demo data ready: super admin admin@jotheca.app · libraries pentecost/accra-central/ashesi · ashesi invite code {}", ASHESI_INVITE_CODE);
    }

    /** Phase 6 demo: a PERSONAL author library with two own books, one for sale (GH₵350). */
    private void seedAuthorLibrary() {
        User author = user("author@jotheca.app", "Author12345!", "Esi Marfo", GlobalRole.USER);
        Library auntie = library(AUTHOR_UID, "auntie-esi-writes", "Auntie Esi Writes",
                LibraryType.PERSONAL, JoinPolicy.PUBLIC, "Accra",
                "Ghanaian novelist Esi Marfo's personal collection — signed copies of her own books.",
                "By appointment");
        auntie.setMemberCount(1);
        libraries.save(auntie);
        Membership owner = member(auntie, author, LibraryRole.LIBRARY_ADMIN, MembershipType.PUBLIC_MEMBER,
                MembershipStatus.APPROVED, "MBR-AW0001");
        memberships.save(owner);
        attachPlan(auntie, "STARTER");

        Branch desk = new Branch();
        desk.setTenantId(auntie.getId());
        desk.setName("Home Studio");
        desk.setCode("AW");
        desk.setMain(true);
        branches.save(desk);

        Category fiction = category(auntie, null, "Ghanaian Fiction", 1);

        Book whispers = addBook(auntie, "The Market of Whispers", "Esi Marfo", "9789988311001", fiction,
                "Sankofa Press", "2024",
                "A market woman in Makola hears every secret in Accra — and one she was never meant to keep.",
                new String[][]{{"AW-000001", null}, {"AW-000002", null}}, null);
        // Audit P3 (E15/E18): the demo author also carries the author capability — a public
        // profile (pen name, NEVER the legal one) with both seeded books linked to it.
        AuthorProfile profile = new AuthorProfile();
        profile.setUserId(author.getId());
        profile.setSlug("auntie-esi");
        profile.setPenName("Auntie Esi");
        profile.setBio("Ghanaian novelist writing the Accra she grew up in — markets after "
                + "dusk, compounds at dawn, and the people who hold both together.");
        profile.setCountry("Ghana");
        profile.setLanguages("English, Twi");
        profile.setGenres("Literary fiction, Short stories");
        profile.setAuthorType(AuthorProfile.AuthorType.INDIE.name());
        profile.setPublicProfile(true);
        authorProfiles.save(profile);

        whispers.setAuthorUserId(author.getId());
        whispers.setAuthorProfileId(profile.getId());
        whispers.setSalePriceMinor(35000L); // GH₵350.00
        whispers.setContactNote("Signed by the author at pickup.");
        books.save(whispers);

        Book night = addBook(auntie, "Accra by Night", "Esi Marfo", "9789988311002", fiction,
                "Sankofa Press", "2021",
                "Eleven interlocking short stories set between Osu and Jamestown after dark.",
                new String[][]{{"AW-000003", null}}, null);
        night.setAuthorUserId(author.getId());
        night.setAuthorProfileId(profile.getId());
        books.save(night);
    }

    /** Phase 5 demo: the patron's current loan of "Homegoing" (copy AC-000005) + its notification. */
    private void seedCirculation(Library accra, User patron) {
        BookCopy copy = copies.findByTenantIdAndBarcode(accra.getId(), "AC-000005").orElseThrow();
        Membership member = memberships.findByTenantIdAndUserId(accra.getId(), patron.getId()).orElseThrow();
        copy.setStatus(CopyStatus.BORROWED);
        copies.save(copy);

        Loan loan = new Loan();
        loan.setTenantId(accra.getId());
        loan.setBookId(copy.getBookId());
        loan.setCopyId(copy.getId());
        loan.setMembershipId(member.getId());
        loan.setUserId(patron.getId());
        loan.setIssuedById(patron.getId()); // seeded history: issuer not meaningful for the fixture
        loan.setIssuedAt(Instant.now().minus(4, java.time.temporal.ChronoUnit.DAYS));
        loan.setDueAt(Instant.now().plus(10, java.time.temporal.ChronoUnit.DAYS));
        loans.save(loan);

        AppNotification n = new AppNotification();
        n.setTenantId(accra.getId());
        n.setUserId(patron.getId());
        n.setType("LOAN_ISSUED");
        n.setTitle("Loan issued: Homegoing");
        n.setBody("\"Homegoing\" (copy AC-000005) is due back on "
                + loan.getDueAt().toString().substring(0, 10) + ".");
        notifications.save(n);
    }

    /** Phase 4 demo catalog: categories, a main branch with shelf locations, books and copies. */
    private void seedCatalog(Library library, String code) {
        boolean accra = ACCRA_UID.equals(library.getUid());

        Branch main = new Branch();
        main.setTenantId(library.getId());
        main.setName("Main Branch");
        main.setCode(code);
        main.setMain(true);
        branches.save(main);

        Category fiction = category(library, null, "Fiction", 1);
        Category africanLit = category(library, fiction, "African Literature", 1);
        Category computing = category(library, null, "Computer Science", 2);
        Category biography = category(library, null, "Biography & History", 3);

        ShelfLocation a12 = shelf(library, main, "Main", "2",
                accra ? "African Literature" : "Reference", "A-12", null);
        ShelfLocation c04 = shelf(library, main, "Main", "1", "Computing", "C-04", "3");

        if (accra) {
            addBook(library, "Things Fall Apart", "Chinua Achebe", "9780385474542", africanLit,
                    "Penguin Classics", "1958",
                    "The classic novel of pre-colonial Igbo life and the arrival of Europeans.",
                    new String[][]{{"AC-000001", null}, {"AC-000002", null}, {"AC-000003", "DAMAGED"}}, a12);
            addBook(library, "The Beautyful Ones Are Not Yet Born", "Ayi Kwei Armah", "9780435905478",
                    africanLit, "Heinemann", "1968",
                    "A Ghanaian railway clerk resists the corruption around him in post-independence Ghana.",
                    new String[][]{{"AC-000004", null}}, a12);
            addBook(library, "Homegoing", "Yaa Gyasi", "9781101947135", africanLit,
                    "Knopf", "2016",
                    "Two half-sisters' divergent destinies traced across eight generations from Ghana to America.",
                    new String[][]{{"AC-000005", null}, {"AC-000006", null}}, a12);
            addBook(library, "Changes: A Love Story", "Ama Ata Aidoo", "9780704342498", africanLit,
                    "The Women's Press", "1991",
                    "A Ghanaian woman's life upended by love, career and convention in Accra.",
                    new String[][]{{"AC-000007", null}}, a12);
            addBook(library, "Clean Code", "Robert C. Martin", "9780132350884", computing,
                    "Prentice Hall", "2008",
                    "A handbook of agile software craftsmanship.",
                    new String[][]{{"AC-000008", null}, {"AC-000009", "MAINTENANCE"}}, c04);
            addBook(library, "Introduction to Algorithms", "Thomas H. Cormen", "9780262033848", computing,
                    "MIT Press", "2009",
                    "The standard algorithms textbook ('CLRS').",
                    new String[][]{{"AC-000010", null}}, c04);
            addBook(library, "Long Walk to Freedom", "Nelson Mandela", "9780316548182", biography,
                    "Little, Brown", "1994",
                    "The autobiography of Nelson Mandela.",
                    new String[][]{{"AC-000011", null}, {"AC-000012", null}}, null);
            addBook(library, "Sapiens: A Brief History of Humankind", "Yuval Noah Harari", "9780062316097",
                    biography, "Harper", "2015",
                    "How Homo sapiens came to dominate the Earth.",
                    new String[][]{{"AC-000013", null}}, null);
        } else {
            addBook(library, "Database System Concepts", "Abraham Silberschatz", "9780078022159", computing,
                    "McGraw-Hill", "2019",
                    "Comprehensive database systems textbook.",
                    new String[][]{{"PU-000001", null}, {"PU-000002", null}}, c04);
            addBook(library, "Operating System Concepts", "Abraham Silberschatz", "9781118063330", computing,
                    "Wiley", "2012",
                    "The 'dinosaur book' on operating systems.",
                    new String[][]{{"PU-000003", null}}, c04);
            addBook(library, "Christian Ethics in Africa", "Samuel M. Dawuni", "9780192837464", biography,
                    "Oxford University Press", "2005",
                    "Staff review copy — not yet released to patrons.",
                    new String[][]{{"PU-000004", null}}, null).setPublished(false);
        }
    }

    private Category category(Library library, Category parent, String name, int order) {
        Category c = new Category();
        c.setTenantId(library.getId());
        c.setParentId(parent == null ? null : parent.getId());
        c.setName(name);
        c.setKind(name);
        c.setSortOrder(order);
        return categories.save(c);
    }

    private ShelfLocation shelf(Library library, Branch branch, String building, String floor,
                                String section, String shelf, String row) {
        ShelfLocation s = new ShelfLocation();
        s.setTenantId(library.getId());
        s.setBranchId(branch.getId());
        s.setBuilding(building);
        s.setFloor(floor);
        s.setSection(section);
        s.setShelf(shelf);
        s.setRowLabel(row);
        return shelfLocations.save(s);
    }

    private Book addBook(Library library, String title, String author, String isbn, Category category,
                         String publisher, String year, String description, String[][] copySpecs,
                         ShelfLocation shelf) {
        Book b = new Book();
        b.setTenantId(library.getId());
        b.setTitle(title);
        b.setAuthor(author);
        b.setIsbn(isbn);
        b.setPublisher(publisher);
        b.setPublishedDate(year);
        b.setLanguage("English");
        b.setDescription(description);
        b.setCategoryId(category.getId());
        b.setSlug(CodeGenerator.slugify(title) + "-" + CodeGenerator.randomToken(4).toLowerCase());
        books.save(b);
        for (int i = 0; i < copySpecs.length; i++) {
            BookCopy copy = new BookCopy();
            copy.setTenantId(library.getId());
            copy.setBookId(b.getId());
            copy.setBarcode(copySpecs[i][0]);
            copy.setAccessionNumber("ACC-" + copySpecs[i][0]);
            copy.setStatus(copySpecs[i][1] == null ? CopyStatus.AVAILABLE : CopyStatus.valueOf(copySpecs[i][1]));
            if (shelf != null) {
                copy.setBranchId(shelf.getBranchId());
                copy.setShelfLocationId(shelf.getId());
            } else {
                copy.setBranchId(mainBranchOf(library));
            }
            copies.save(copy);
        }
        return b;
    }

    private String mainBranchOf(Library library) {
        return branches.findFirstByTenantIdAndMainTrue(library.getId()).map(Branch::getId).orElse(null);
    }

    private void attachPlan(Library library, String planCode) {
        SubscriptionPlan plan = plans.findByCode(planCode)
                .orElseThrow(() -> new IllegalStateException("seed plan missing: " + planCode));
        LibrarySubscription sub = new LibrarySubscription();
        sub.setTenantId(library.getId());
        sub.setPlanId(plan.getId());
        sub.setCurrentPeriodStart(Instant.now());
        sub.setCurrentPeriodEnd(Instant.now().plus(30, java.time.temporal.ChronoUnit.DAYS));
        subscriptions.save(sub);
    }

    private User user(String email, String password, String name, GlobalRole role) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(password));
        u.setFullName(name);
        u.setGlobalRole(role);
        u.setEmailVerifiedAt(Instant.now());
        return users.save(u);
    }

    private Library library(String uid, String slug, String name, LibraryType type, JoinPolicy policy,
                            String city, String description, String openingHours) {
        Library l = new Library();
        l.setUid(uid);
        l.setSlug(slug);
        l.setName(name);
        l.setType(type);
        l.setStatus(LibraryStatus.ACTIVE);
        l.setJoinPolicy(policy);
        l.setCountry("Ghana");
        l.setCity(city);
        l.setDescription(description);
        l.setOpeningHours(openingHours);
        l.setServices("Lending, Reading rooms, Research support");
        l.setVisibility(true);
        l.setMemberCount(0);
        l.setAllowAnonymousReviews(false);      // safe default; enabled explicitly per demo library
        return libraries.save(l);
    }

    private Membership member(Library library, User user, LibraryRole role, MembershipType type,
                              MembershipStatus status, String number) {
        Membership m = new Membership();
        m.setTenantId(library.getId());
        m.setUserId(user.getId());
        m.setRole(role);
        m.setMembershipType(type);
        m.setStatus(status);
        m.setMembershipNumber(number);
        if (status == MembershipStatus.APPROVED) {
            m.setJoinedAt(Instant.now());
        }
        return m;
    }
}
