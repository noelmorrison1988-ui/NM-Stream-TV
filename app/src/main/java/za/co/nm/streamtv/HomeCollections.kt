package za.co.nm.streamtv

/** TMDB discovery categories. Genres and curated searches are discovery hints,
 * not a promise that any given provider can play a title. */
data class HomeCollectionQuery(
    val kind: String,
    val route: String,
    val searchName: String? = null
)

data class HomeCollectionSpec(
    val key: String,
    val title: String,
    val queries: List<HomeCollectionQuery>
)

object HomeCollections {
    private fun movies(genres: String, rating: String = "") =
        HomeCollectionQuery(
            "movie",
            "discover/movie?sort_by=popularity.desc&include_adult=false&with_genres=$genres" +
                if (rating.isBlank()) "" else "&certification_country=US&certification.lte=$rating"
        )
    private fun shows(genres: String) =
        HomeCollectionQuery("series", "discover/tv?sort_by=popularity.desc&with_genres=$genres")
    private fun movie(name: String) = HomeCollectionQuery("movie", "search/movie", name)
    private fun show(name: String) = HomeCollectionQuery("series", "search/tv", name)

    val rows = listOf(
        HomeCollectionSpec("reality", "Reality", listOf(
            shows("10764"), show("Fast N' Loud"), show("Top Gear"), show("American Pickers")
        )),
        HomeCollectionSpec("comedy", "Comedy", listOf(movies("35"), shows("35"))),
        HomeCollectionSpec("documentary", "Documentary", listOf(movies("99"), shows("99"))),
        HomeCollectionSpec("for_him", "For Him", listOf(
            movies("28|12"), shows("10759"),
            movie("Fast and Furious"), show("Fast N' Loud"), movie("Anchorman"),
            movie("Mission Impossible"), movie("G.I. Joe"), movie("The Hangover"),
            show("Home Improvement"), show("This Old House")
        )),
        HomeCollectionSpec("for_her", "For Her", listOf(
            movies("10749|35"), shows("18|35"),
            movie("The Notebook"), show("Gilmore Girls"), show("Bridgerton"),
            movie("Legally Blonde"), show("Sweet Magnolias")
        )),
        HomeCollectionSpec("teens", "Teens", listOf(
            movies("12|10751", "PG-13"),
            show("Wednesday"), show("Heartstopper"), show("Young Sheldon"),
            show("Percy Jackson and the Olympians"), movie("High School Musical")
        )),
        HomeCollectionSpec("kids", "Kids", listOf(
            movies("16|10751", "PG"), shows("10762"),
            show("SpongeBob SquarePants"), show("The Amazing World of Gumball"),
            show("Adventure Time"), show("Teen Titans Go!"), show("The Loud House"),
            show("Henry Danger"), show("PAW Patrol")
        )),
        HomeCollectionSpec("marvel", "Marvel", listOf(
            movie("Avengers"), movie("Iron Man"), movie("Captain America"),
            movie("Thor"), movie("Spider-Man"), movie("Guardians of the Galaxy"),
            show("Loki"), show("WandaVision")
        )),
        HomeCollectionSpec("dc", "DC", listOf(
            movie("Batman"), movie("Superman"), movie("Wonder Woman"),
            movie("Justice League"), movie("Aquaman"), movie("Shazam"),
            show("The Flash"), show("Peacemaker")
        )),
        HomeCollectionSpec("wrestling", "Wrestling", listOf(
            show("WWE Raw"), show("WWE SmackDown"), show("AEW Dynamite"),
            show("AEW Collision"), show("WWE NXT"),
            show("WWE WrestleMania"), show("AEW Rampage")
        ))
    )

    val byKey = rows.associateBy { it.key }
}
