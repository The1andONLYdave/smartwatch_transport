/*
 * Copyright 2010 the original author or authors.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.pte;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.schildbach.pte.dto.Connection;
import de.schildbach.pte.dto.Departure;
import de.schildbach.pte.dto.GetConnectionDetailsResult;
import de.schildbach.pte.dto.Location;
import de.schildbach.pte.dto.LocationType;
import de.schildbach.pte.dto.NearbyStationsResult;
import de.schildbach.pte.dto.QueryConnectionsResult;
import de.schildbach.pte.dto.QueryDeparturesResult;
import de.schildbach.pte.dto.QueryDeparturesResult.Status;
import de.schildbach.pte.dto.Station;
import de.schildbach.pte.exception.SessionExpiredException;
import de.schildbach.pte.util.Color;
import de.schildbach.pte.util.ParserUtils;

/**
 * @author Andreas Schildbach
 */
public final class BvgProvider extends AbstractHafasProvider
{
	public static final NetworkId NETWORK_ID = NetworkId.BVG;
	public static final String OLD_NETWORK_ID = "mobil.bvg.de";

	private static final long PARSER_DAY_ROLLOVER_THRESHOLD_MS = 12 * 60 * 60 * 1000;
	private static final long PARSER_DAY_ROLLDOWN_THRESHOLD_MS = 6 * 60 * 60 * 1000;

	private static final String BVG_BASE_URL = "http://mobil.bvg.de";
	private static final String API_BASE = "http://mobil.bvg.de/Fahrinfo/bin/";

	public BvgProvider()
	{
		super(null, null);
	}

	public NetworkId id()
	{
		return NETWORK_ID;
	}

	public boolean hasCapabilities(final Capability... capabilities)
	{
		for (final Capability capability : capabilities)
			if (capability == Capability.NEARBY_STATIONS)
				return false;

		return true;
	}

	private static final String AUTOCOMPLETE_NAME_URL = "http://mobil.bvg.de/Fahrinfo/bin/stboard.bin/dox/dox?input=%s";
	private static final Pattern P_SINGLE_NAME = Pattern.compile(".*?Haltestelleninfo.*?<strong>(.*?)</strong>.*?input=(\\d+)&.*?", Pattern.DOTALL);
	private static final Pattern P_MULTI_NAME = Pattern.compile("<a href=\\\"/Fahrinfo/bin/stboard\\.bin/dox.*?input=(\\d+)&.*?\">\\s*(.*?)\\s*</a>",
			Pattern.DOTALL);

	@Override
	public List<Location> autocompleteStations(final CharSequence constraint) throws IOException
	{
		final List<Location> results = new ArrayList<Location>();

		final String uri = String.format(AUTOCOMPLETE_NAME_URL, ParserUtils.urlEncode(constraint.toString()));
		final CharSequence page = ParserUtils.scrape(uri);

		final Matcher mSingle = P_SINGLE_NAME.matcher(page);
		if (mSingle.matches())
		{
			results.add(new Location(LocationType.STATION, Integer.parseInt(mSingle.group(2)), null, ParserUtils.resolveEntities(mSingle.group(1))));
		}
		else
		{
			final Matcher mMulti = P_MULTI_NAME.matcher(page);
			while (mMulti.find())
				results.add(new Location(LocationType.STATION, Integer.parseInt(mMulti.group(1)), null, ParserUtils.resolveEntities(mMulti.group(2))));
		}

		return results;
	}

	private final String NEARBY_URI = API_BASE + "stboard.bin/dn?distance=50&near&input=%s";

	@Override
	protected String nearbyStationUri(final String stationId)
	{
		return String.format(NEARBY_URI, ParserUtils.urlEncode(stationId));
	}

	private final static Pattern P_NEARBY_OWN = Pattern
			.compile("/Stadtplan/index.*?location=(\\d+),HST,WGS84,(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)&amp;label=([^\"]*)\"");
	private final static Pattern P_NEARBY_PAGE = Pattern.compile("<table class=\"ivuTableOverview\".*?<tbody>(.*?)</tbody>", Pattern.DOTALL);
	private final static Pattern P_NEARBY_COARSE = Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL);
	private final static Pattern P_NEARBY_FINE_LOCATION = Pattern.compile("input=(\\d+)&[^\"]*\">([^<]*)<");
	private static final Pattern P_NEARBY_ERRORS = Pattern.compile("(derzeit leider nicht bearbeitet werden)");

	@Override
	public NearbyStationsResult nearbyStations(final String stationId, final int lat, final int lon, final int maxDistance, final int maxStations)
			throws IOException
	{
		if (stationId == null)
			throw new IllegalArgumentException("stationId must be given");

		final List<Station> stations = new ArrayList<Station>();

		final String uri = nearbyStationUri(stationId);
		final CharSequence page = ParserUtils.scrape(uri);

		final Matcher mError = P_NEARBY_ERRORS.matcher(page);
		if (mError.find())
		{
			if (mError.group(1) != null)
				return new NearbyStationsResult(NearbyStationsResult.Status.INVALID_STATION);
		}

		final Matcher mOwn = P_NEARBY_OWN.matcher(page);
		if (mOwn.find())
		{
			final int parsedId = Integer.parseInt(mOwn.group(1));
			final int parsedLon = (int) (Float.parseFloat(mOwn.group(2)) * 1E6);
			final int parsedLat = (int) (Float.parseFloat(mOwn.group(3)) * 1E6);
			final String parsedName = ParserUtils.urlDecode(mOwn.group(4), "ISO-8859-1");
			stations.add(newStation(parsedId, parsedName, parsedLat, parsedLon));
		}

		final Matcher mPage = P_NEARBY_PAGE.matcher(page);
		if (mPage.find())
		{
			final Matcher mCoarse = P_NEARBY_COARSE.matcher(mPage.group(1));

			while (mCoarse.find())
			{
				final Matcher mFineLocation = P_NEARBY_FINE_LOCATION.matcher(mCoarse.group(1));

				if (mFineLocation.find())
				{
					final int parsedId = Integer.parseInt(mFineLocation.group(1));
					final String parsedName = ParserUtils.resolveEntities(mFineLocation.group(2));
					final Station station = newStation(parsedId, parsedName, 0, 0);
					if (!stations.contains(station))
						stations.add(station);
				}
				else
				{
					throw new IllegalArgumentException("cannot parse '" + mCoarse.group(1) + "' on " + uri);
				}
			}

			if (maxStations == 0 || maxStations >= stations.size())
				return new NearbyStationsResult(stations);
			else
				return new NearbyStationsResult(stations.subList(0, maxStations));
		}
		else
		{
			throw new IllegalArgumentException("cannot parse '" + page + "' on " + uri);
		}
	}

	private Station newStation(final int id, final String parsedName, final int lat, final int lon)
	{
		String place = null, name = null, longName = null;

		if (parsedName.endsWith(" (Berlin)"))
		{
			place = "Berlin";
			name = parsedName.substring(0, parsedName.length() - 9);
		}
		else if (parsedName.startsWith("Potsdam, "))
		{
			place = "Potsdam";
			name = parsedName.substring(9);
		}
		else
		{
			longName = parsedName;
		}

		return new Station(id, place, name, longName, lat, lon, 0, null, null);
	}

	public static final String STATION_URL_CONNECTION = "http://mobil.bvg.de/Fahrinfo/bin/query.bin/dox";

	private String connectionsQueryUri(final Location from, final Location via, final Location to, final Date date, final boolean dep,
			final String products)
	{
		final DateFormat DATE_FORMAT = new SimpleDateFormat("dd.MM.yy");
		final DateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm");
		final StringBuilder uri = new StringBuilder();

		uri.append("http://mobil.bvg.de/Fahrinfo/bin/query.bin/dox");
		uri.append("?REQ0HafasInitialSelection=0");

		appendLocationBvg(uri, from, "S0", "SID");
		appendLocationBvg(uri, to, "Z0", "ZID");
		if (via != null)
			appendLocationBvg(uri, via, "1", null);

		uri.append("&REQ0HafasSearchForw=").append(dep ? "1" : "0");
		uri.append("&REQ0JourneyDate=").append(ParserUtils.urlEncode(DATE_FORMAT.format(date)));
		uri.append("&REQ0JourneyTime=").append(ParserUtils.urlEncode(TIME_FORMAT.format(date)));

		for (final char p : products.toCharArray())
		{
			if (p == 'I')
				uri.append("&vw=5");
			if (p == 'R')
				uri.append("&vw=6");
			if (p == 'S')
				uri.append("&vw=0");
			if (p == 'U')
				uri.append("&vw=1");
			if (p == 'T')
				uri.append("&vw=2");
			if (p == 'B')
				uri.append("&vw=3");
			if (p == 'F')
				uri.append("&vw=4");
			// FIXME if (p == 'C')
			// TODO Ruftaxi wäre wohl &vw=7
		}

		uri.append("&start=Suchen");
		return uri.toString();
	}

	private static final void appendLocationBvg(final StringBuilder uri, final Location location, final String paramSuffix, final String paramWgs)
	{
		if (location.type == LocationType.ADDRESS && location.lat != 0 && location.lon != 0 && paramWgs != null)
		{
			uri.append("&").append(paramWgs).append("=").append(ParserUtils.urlEncode("A=16@X=" + location.lon + "@Y=" + location.lat));
		}
		else
		{
			uri.append("&REQ0JourneyStops").append(paramSuffix).append("A=").append(locationTypeValue(location));
			uri.append("&REQ0JourneyStops").append(paramSuffix).append("G=").append(ParserUtils.urlEncode(locationValue(location)));
		}
	}

	private static final int locationTypeValue(final Location location)
	{
		final LocationType type = location.type;
		if (type == LocationType.STATION)
			return 1;
		if (type == LocationType.ADDRESS)
			return 2;
		if (type == LocationType.ANY)
			return 255;
		throw new IllegalArgumentException(type.toString());
	}

	private static final String locationValue(final Location location)
	{
		if (location.type == LocationType.STATION && location.id >= 1000000)
			return Integer.toString(location.id);
		else
			return location.name;
	}

	private static final Pattern P_CHECK_ADDRESS = Pattern.compile("<option[^>]*>\\s*(.*?)\\s*</option>", Pattern.DOTALL);
	private static final Pattern P_CHECK_FROM = Pattern.compile("Von:");
	private static final Pattern P_CHECK_TO = Pattern.compile("Nach:");
	private static final Pattern P_CHECK_CONNECTIONS_ERROR = Pattern
			.compile("(zu dicht beieinander|mehrfach vorhanden oder identisch)|(keine geeigneten Haltestellen)|(keine Verbindung gefunden)|(derzeit nur Ausk&#252;nfte vom)|(zwischenzeitlich nicht mehr gespeichert)");

	@Override
	public QueryConnectionsResult queryConnections(final Location from, final Location via, final Location to, final Date date, final boolean dep,
			final String products, final WalkSpeed walkSpeed) throws IOException
	{
		final String uri = connectionsQueryUri(from, via, to, date, dep, products);
		final CharSequence page = ParserUtils.scrape(uri);

		final Matcher mAddress = P_CHECK_ADDRESS.matcher(page);

		final List<Location> addresses = new ArrayList<Location>();
		while (mAddress.find())
		{
			final String address = ParserUtils.resolveEntities(mAddress.group(1));
			if (!addresses.contains(address))
				addresses.add(new Location(LocationType.ANY, 0, null, address + "!"));
		}

		if (addresses.isEmpty())
		{
			return queryConnections(uri, page);
		}
		else if (P_CHECK_FROM.matcher(page).find())
		{
			if (P_CHECK_TO.matcher(page).find())
				return new QueryConnectionsResult(null, addresses, null);
			else
				return new QueryConnectionsResult(null, null, addresses);
		}
		else
		{
			return new QueryConnectionsResult(addresses, null, null);
		}
	}

	@Override
	public QueryConnectionsResult queryMoreConnections(final String uri) throws IOException
	{
		final CharSequence page = ParserUtils.scrape(uri);
		return queryConnections(uri, page);
	}

	private static final Pattern P_CONNECTIONS_HEAD = Pattern.compile(".*?" //
			+ "Von: <strong>(.*?)</strong>.*?" // from
			+ "Nach: <strong>(.*?)</strong>.*?" // to
			+ "Datum: .., (.*?)<br />.*?" // currentDate
			+ "(?:<a href=\"(/Fahrinfo/bin/query\\.bin/dox[^\"]*?ScrollDir=2)\">.*?)?" // linkEarlier
			+ "(?:<a href=\"(/Fahrinfo/bin/query\\.bin/dox[^\"]*?ScrollDir=1)\">.*?)?" // linkLater
	, Pattern.DOTALL);
	private static final Pattern P_CONNECTIONS_COARSE = Pattern.compile("<p class=\"con(?:L|D)\">(.+?)</p>", Pattern.DOTALL);
	private static final Pattern P_CONNECTIONS_FINE = Pattern.compile(".*?" //
			+ "<a href=\"(/Fahrinfo/bin/query\\.bin/dox[^\"]*?)\">" // link
			+ "(\\d\\d:\\d\\d)-(\\d\\d:\\d\\d)</a>&nbsp;&nbsp;(?:\\d+ Umst\\.|([\\w\\d ]+)).*?" //
	, Pattern.DOTALL);

	private QueryConnectionsResult queryConnections(final String uri, final CharSequence page) throws IOException
	{
		final Matcher mError = P_CHECK_CONNECTIONS_ERROR.matcher(page);
		if (mError.find())
		{
			if (mError.group(1) != null)
				return QueryConnectionsResult.TOO_CLOSE;
			if (mError.group(2) != null)
				return QueryConnectionsResult.UNRESOLVABLE_ADDRESS;
			if (mError.group(3) != null)
				return QueryConnectionsResult.NO_CONNECTIONS;
			if (mError.group(4) != null)
				return QueryConnectionsResult.INVALID_DATE;
			if (mError.group(5) != null)
				throw new SessionExpiredException();
		}

		final Matcher mHead = P_CONNECTIONS_HEAD.matcher(page);
		if (mHead.matches())
		{
			final Location from = new Location(LocationType.ANY, 0, null, ParserUtils.resolveEntities(mHead.group(1)));
			final Location to = new Location(LocationType.ANY, 0, null, ParserUtils.resolveEntities(mHead.group(2)));
			final Date currentDate = ParserUtils.parseDate(mHead.group(3));
			final String linkEarlier = mHead.group(4) != null ? BVG_BASE_URL + ParserUtils.resolveEntities(mHead.group(4)) : null;
			final String linkLater = mHead.group(5) != null ? BVG_BASE_URL + ParserUtils.resolveEntities(mHead.group(5)) : null;
			final List<Connection> connections = new ArrayList<Connection>();

			final Matcher mConCoarse = P_CONNECTIONS_COARSE.matcher(page);
			while (mConCoarse.find())
			{
				final Matcher mConFine = P_CONNECTIONS_FINE.matcher(mConCoarse.group(1));
				if (mConFine.matches())
				{
					final String link = BVG_BASE_URL + ParserUtils.resolveEntities(mConFine.group(1));
					Date departureTime = ParserUtils.joinDateTime(currentDate, ParserUtils.parseTime(mConFine.group(2)));
					if (!connections.isEmpty())
					{
						final long diff = ParserUtils.timeDiff(departureTime, connections.get(connections.size() - 1).departureTime);
						if (diff > PARSER_DAY_ROLLOVER_THRESHOLD_MS)
							departureTime = ParserUtils.addDays(departureTime, -1);
						else if (diff < -PARSER_DAY_ROLLDOWN_THRESHOLD_MS)
							departureTime = ParserUtils.addDays(departureTime, 1);
					}
					Date arrivalTime = ParserUtils.joinDateTime(currentDate, ParserUtils.parseTime(mConFine.group(3)));
					if (departureTime.after(arrivalTime))
						arrivalTime = ParserUtils.addDays(arrivalTime, 1);
					final String line = normalizeLine(ParserUtils.resolveEntities(mConFine.group(4)));
					final Connection connection = new Connection(AbstractHafasProvider.extractConnectionId(link), link, departureTime, arrivalTime,
							line, line != null ? lineColors(line) : null, 0, from.name, 0, to.name, null, null);
					connections.add(connection);
				}
				else
				{
					throw new IllegalArgumentException("cannot parse '" + mConCoarse.group(1) + "' on " + uri);
				}
			}

			return new QueryConnectionsResult(uri, from, null, to, linkLater, connections);
		}
		else
		{
			throw new IOException(page.toString());
		}
	}

	private static final Pattern P_CONNECTION_DETAILS_HEAD = Pattern.compile(".*(?:Datum|Abfahrt): (\\d\\d\\.\\d\\d\\.\\d\\d).*", Pattern.DOTALL);
	private static final Pattern P_CONNECTION_DETAILS_COARSE = Pattern.compile("<p class=\"con\\w\">\n(.+?)</p>", Pattern.DOTALL);
	static final Pattern P_CONNECTION_DETAILS_FINE = Pattern.compile("(?:<a href=\".*?input=(\\d+).*?\">(?:\n<strong>)?" // departureId
			+ "(.+?)(?:</strong>\n)?</a>)?.*?" // departure
			+ "(?:" //
			+ "ab (\\d+:\\d+)\n" // departureTime
			+ "(Gl\\. \\d+)?.*?" // departurePosition
			+ "<strong>\\s*(.*?)\\s*</strong>.*?" // line
			+ "Ri\\. (.*?)[\n\\.]*<.*?" // destination
			+ "an (\\d+:\\d+)\n" // arrivalTime
			+ "(Gl\\. \\d+)?.*?" // arrivalPosition
			+ "<a href=\".*?input=(\\d+).*?\">\n" // arrivalId
			+ "<strong>(.*?)</strong>" // arrival
			+ "|" //
			+ "(\\d+) Min\\.\n" // footway
			+ "(?:Fussweg|&#220;bergang)\n" //
			+ "<br />\n" //
			+ "(?:<a href=\"/Fahrinfo.*?input=(\\d+)\">\n" // arrivalId
			+ "<strong>(.*?)</strong>|<a href=\"/Stadtplan.*?WGS84,(\\d+),(\\d+)&.*?\">([^<]*)</a>|<strong>([^<]*)</strong>).*?" // arrival
			+ ").*?", Pattern.DOTALL);

	@Override
	public GetConnectionDetailsResult getConnectionDetails(final String uri) throws IOException
	{
		final CharSequence page = ParserUtils.scrape(uri);

		final Matcher mHead = P_CONNECTION_DETAILS_HEAD.matcher(page);
		if (mHead.matches())
		{
			final Date currentDate = ParserUtils.parseDate(mHead.group(1));
			final List<Connection.Part> parts = new ArrayList<Connection.Part>(4);

			Date firstDepartureTime = null;
			String firstDeparture = null;
			int firstDepartureId = 0;
			Date lastArrivalTime = null;
			String lastArrival = null;
			int lastArrivalId = 0;

			final Matcher mDetCoarse = P_CONNECTION_DETAILS_COARSE.matcher(page);
			while (mDetCoarse.find())
			{
				final Matcher mDetFine = P_CONNECTION_DETAILS_FINE.matcher(mDetCoarse.group(1));
				if (mDetFine.matches())
				{
					int departureId = 0;
					String departure = ParserUtils.resolveEntities(mDetFine.group(2));
					if (departure == null)
					{
						departure = lastArrival;
						departureId = lastArrivalId;
					}
					else
					{
						departureId = Integer.parseInt(mDetFine.group(1));
					}

					if (departure != null && firstDeparture == null)
					{
						firstDeparture = departure;
						firstDepartureId = departureId;
					}

					final String min = mDetFine.group(11);
					if (min == null)
					{
						Date departureTime = ParserUtils.joinDateTime(currentDate, ParserUtils.parseTime(mDetFine.group(3)));
						if (lastArrivalTime != null && departureTime.before(lastArrivalTime))
							departureTime = ParserUtils.addDays(departureTime, 1);

						final String departurePosition = mDetFine.group(4);

						final String line = normalizeLine(ParserUtils.resolveEntities(mDetFine.group(5)));

						final Location destination = new Location(LocationType.ANY, 0, null, ParserUtils.resolveEntities(mDetFine.group(6)));

						Date arrivalTime = ParserUtils.joinDateTime(currentDate, ParserUtils.parseTime(mDetFine.group(7)));
						if (departureTime.after(arrivalTime))
							arrivalTime = ParserUtils.addDays(arrivalTime, 1);

						final String arrivalPosition = mDetFine.group(8);

						final int arrivalId = Integer.parseInt(mDetFine.group(9));

						final String arrival = ParserUtils.resolveEntities(mDetFine.group(10));

						parts.add(new Connection.Trip(line, line != null ? lineColors(line) : null, destination, departureTime, departurePosition,
								departureId, departure, arrivalTime, arrivalPosition, arrivalId, arrival, null));

						if (firstDepartureTime == null)
							firstDepartureTime = departureTime;

						lastArrival = arrival;
						lastArrivalId = arrivalId;
						lastArrivalTime = arrivalTime;
					}
					else
					{
						final int arrivalId = mDetFine.group(12) != null ? Integer.parseInt(mDetFine.group(12)) : 0;

						final String arrival = ParserUtils.resolveEntities(ParserUtils.selectNotNull(mDetFine.group(13), mDetFine.group(16),
								mDetFine.group(17)));

						final int arrivalLon = mDetFine.group(14) != null ? Integer.parseInt(mDetFine.group(14)) : 0;

						final int arrivalLat = mDetFine.group(15) != null ? Integer.parseInt(mDetFine.group(15)) : 0;

						if (parts.size() > 0 && parts.get(parts.size() - 1) instanceof Connection.Footway)
						{
							final Connection.Footway lastFootway = (Connection.Footway) parts.remove(parts.size() - 1);
							parts.add(new Connection.Footway(lastFootway.min + Integer.parseInt(min), lastFootway.departureId, lastFootway.departure,
									arrivalId, arrival, arrivalLat, arrivalLon));
						}
						else
						{
							parts.add(new Connection.Footway(Integer.parseInt(min), departureId, departure, arrivalId, arrival, arrivalLat,
									arrivalLon));
						}

						lastArrival = arrival;
						lastArrivalId = arrivalId;
					}
				}
				else
				{
					throw new IllegalArgumentException("cannot parse '" + mDetCoarse.group(1) + "' on " + uri);
				}
			}

			if (firstDepartureTime != null && lastArrivalTime != null)
				return new GetConnectionDetailsResult(currentDate, new Connection(AbstractHafasProvider.extractConnectionId(uri), uri,
						firstDepartureTime, lastArrivalTime, null, null, firstDepartureId, firstDeparture, lastArrivalId, lastArrival, parts, null));
			else
				return new GetConnectionDetailsResult(currentDate, null);
		}
		else
		{
			throw new IOException(page.toString());
		}
	}

	private static final String DEPARTURE_URL_LIVE = "http://mobil.bvg.de/IstAbfahrtzeiten/index/mobil?";

	private String departuresQueryLiveUri(final String stationId)
	{
		final StringBuilder uri = new StringBuilder();
		uri.append(DEPARTURE_URL_LIVE);
		uri.append("input=").append(stationId);
		return uri.toString();
	}

	private static final String DEPARTURE_URL_PLAN = "http://mobil.bvg.de/Fahrinfo/bin/stboard.bin/dox/dox?boardType=dep&disableEquivs=yes&start=yes&";

	private String departuresQueryPlanUri(final String stationId, final int maxDepartures)
	{
		final StringBuilder uri = new StringBuilder();
		uri.append(DEPARTURE_URL_PLAN);
		uri.append("input=").append(stationId);
		uri.append("&maxJourneys=").append(maxDepartures != 0 ? maxDepartures : 50);
		return uri.toString();
	}

	private static final Pattern P_DEPARTURES_HEAD = Pattern.compile(".*?" //
			+ "<strong>(.*?)</strong>.*?Datum:(.*?)<br />.*" //
	, Pattern.DOTALL);
	private static final Pattern P_DEPARTURES_COARSE = Pattern.compile("" //
			+ "<tr class=\"ivu_table_bg\\d\">\\s*((?:<td class=\"ivu_table_c_dep\">|<td>).+?)\\s*</tr>" //
	, Pattern.DOTALL);
	private static final Pattern P_DEPARTURES_LIVE_FINE = Pattern.compile("" //
			+ "<td class=\"ivu_table_c_dep\">\\s*(\\d{1,2}:\\d{2})\\s*" // time
			+ "(\\*)?\\s*</td>\\s*" // planned
			+ "<td class=\"ivu_table_c_line\">\\s*(.*?)\\s*</td>\\s*" // line
			+ "<td>.*?<a.*?[^-]>\\s*(.*?)\\s*</a>.*?</td>" // destination
	, Pattern.DOTALL);
	private static final Pattern P_DEPARTURES_PLAN_FINE = Pattern.compile("" //
			+ "<td><strong>(\\d{1,2}:\\d{2})</strong></td>.*?" // time
			+ "<strong>\\s*(.*?)[\\s\\*]*</strong>.*?" // line
			+ "(?:\\((Gl\\. " + ParserUtils.P_PLATFORM + ")\\).*?)?" // position
			+ "<a href=\"/Fahrinfo/bin/stboard\\.bin/dox/dox.*?evaId=(\\d+)&[^>]*>" // destinationId
			+ "\\s*(.*?)\\s*</a>.*?" // destination
	, Pattern.DOTALL);
	private static final Pattern P_DEPARTURES_LIVE_ERRORS = Pattern.compile("(Haltestelle:)|(Wartungsgr&uuml;nden)");
	private static final Pattern P_DEPARTURES_PLAN_ERRORS = Pattern.compile("(derzeit leider nicht bearbeitet werden)|(Wartungsarbeiten)");

	public QueryDeparturesResult queryDepartures(final String stationId, final int maxDepartures) throws IOException
	{
		if (stationId.length() == 6) // live
		{
			final String uri = departuresQueryLiveUri(stationId);
			final CharSequence page = ParserUtils.scrape(uri);

			final Matcher mError = P_DEPARTURES_LIVE_ERRORS.matcher(page);
			if (mError.find())
			{
				if (mError.group(1) != null)
					return new QueryDeparturesResult(Status.INVALID_STATION, Integer.parseInt(stationId));
				if (mError.group(2) != null)
					return new QueryDeparturesResult(Status.SERVICE_DOWN, Integer.parseInt(stationId));
			}

			// parse page
			final Matcher mHead = P_DEPARTURES_HEAD.matcher(page);
			if (mHead.matches())
			{
				final String location = ParserUtils.resolveEntities(mHead.group(1));
				final Date currentTime = parseDate(mHead.group(2));
				final List<Departure> departures = new ArrayList<Departure>(8);

				// choose matcher
				final Matcher mDepCoarse = P_DEPARTURES_COARSE.matcher(page);
				while (mDepCoarse.find())
				{
					final Matcher mDepFine = P_DEPARTURES_LIVE_FINE.matcher(mDepCoarse.group(1));
					if (mDepFine.matches())
					{
						final Calendar current = new GregorianCalendar();
						current.setTime(currentTime);
						final Calendar parsed = new GregorianCalendar();
						parsed.setTime(ParserUtils.parseTime(mDepFine.group(1)));
						parsed.set(Calendar.YEAR, current.get(Calendar.YEAR));
						parsed.set(Calendar.MONTH, current.get(Calendar.MONTH));
						parsed.set(Calendar.DAY_OF_MONTH, current.get(Calendar.DAY_OF_MONTH));
						if (ParserUtils.timeDiff(parsed.getTime(), currentTime) < -PARSER_DAY_ROLLOVER_THRESHOLD_MS)
							parsed.add(Calendar.DAY_OF_MONTH, 1);

						boolean isPlanned = mDepFine.group(2) != null;

						Date plannedTime = null;
						Date predictedTime = null;
						if (!isPlanned)
							predictedTime = parsed.getTime();
						else
							plannedTime = parsed.getTime();

						final String line = normalizeLine(ParserUtils.resolveEntities(mDepFine.group(3)));

						final String position = null;

						final int destinationId = 0;

						final String destination = ParserUtils.resolveEntities(mDepFine.group(4));

						final Departure dep = new Departure(plannedTime, predictedTime, line, line != null ? lineColors(line) : null, null, position,
								destinationId, destination, null);
						if (!departures.contains(dep))
							departures.add(dep);
					}
					else
					{
						throw new IllegalArgumentException("cannot parse '" + mDepCoarse.group(1) + "' on " + uri);
					}
				}

				return new QueryDeparturesResult(new Location(LocationType.STATION, Integer.parseInt(stationId), null, location), departures, null);
			}
			else
			{
				throw new IllegalArgumentException("cannot parse '" + page + "' on " + uri);
			}
		}
		else
		{
			final String uri = departuresQueryPlanUri(stationId, maxDepartures);
			final CharSequence page = ParserUtils.scrape(uri);

			final Matcher mError = P_DEPARTURES_PLAN_ERRORS.matcher(page);
			if (mError.find())
			{
				if (mError.group(1) != null)
					return new QueryDeparturesResult(Status.INVALID_STATION, Integer.parseInt(stationId));
				if (mError.group(2) != null)
					return new QueryDeparturesResult(Status.SERVICE_DOWN, Integer.parseInt(stationId));
			}

			// parse page
			final Matcher mHead = P_DEPARTURES_HEAD.matcher(page);
			if (mHead.matches())
			{
				final String location = ParserUtils.resolveEntities(mHead.group(1));
				final Date currentTime = parseDate(mHead.group(2));
				final List<Departure> departures = new ArrayList<Departure>(8);

				// choose matcher
				final Matcher mDepCoarse = P_DEPARTURES_COARSE.matcher(page);
				while (mDepCoarse.find())
				{
					final Matcher mDepFine = P_DEPARTURES_PLAN_FINE.matcher(mDepCoarse.group(1));
					if (mDepFine.matches())
					{
						final Calendar current = new GregorianCalendar();
						current.setTime(currentTime);
						final Calendar parsed = new GregorianCalendar();
						parsed.setTime(ParserUtils.parseTime(mDepFine.group(1)));
						parsed.set(Calendar.YEAR, current.get(Calendar.YEAR));
						parsed.set(Calendar.MONTH, current.get(Calendar.MONTH));
						parsed.set(Calendar.DAY_OF_MONTH, current.get(Calendar.DAY_OF_MONTH));
						if (ParserUtils.timeDiff(parsed.getTime(), currentTime) < -PARSER_DAY_ROLLOVER_THRESHOLD_MS)
							parsed.add(Calendar.DAY_OF_MONTH, 1);

						final Date plannedTime = parsed.getTime();

						final String line = normalizeLine(ParserUtils.resolveEntities(mDepFine.group(2)));

						final String position = ParserUtils.resolveEntities(mDepFine.group(3));

						final int destinationId = Integer.parseInt(mDepFine.group(4));

						final String destination = ParserUtils.resolveEntities(mDepFine.group(5));

						final Departure dep = new Departure(plannedTime, null, line, line != null ? lineColors(line) : null, null, position,
								destinationId, destination, null);
						if (!departures.contains(dep))
							departures.add(dep);
					}
					else
					{
						throw new IllegalArgumentException("cannot parse '" + mDepCoarse.group(1) + "' on " + uri);
					}
				}

				return new QueryDeparturesResult(new Location(LocationType.STATION, Integer.parseInt(stationId), null, location), departures, null);
			}
			else
			{
				throw new IllegalArgumentException("cannot parse '" + page + "' on " + uri);
			}
		}
	}

	private static final Date parseDate(String str)
	{
		try
		{
			return new SimpleDateFormat("dd.MM.yyyy, HH:mm:ss").parse(str);
		}
		catch (ParseException x1)
		{
			try
			{
				return new SimpleDateFormat("dd.MM.yy").parse(str);
			}
			catch (ParseException x2)
			{
				throw new RuntimeException(x2);
			}
		}
	}

	private static final Pattern P_NORMALIZE_LINE = Pattern.compile("([A-Za-zÄÖÜäöüßáàâéèêíìîóòôúùû]+)[\\s-]*(.*)");
	private static final Pattern P_NORMALIZE_LINE_SPECIAL_NUMBER = Pattern.compile("\\d{4,}");
	private static final Pattern P_NORMALIZE_LINE_SPECIAL_BUS = Pattern.compile("Bus[A-Z]");

	private static String normalizeLine(final String line)
	{
		if (line == null || line.length() == 0)
			return null;

		if (line.startsWith("RE") || line.startsWith("RB") || line.startsWith("NE") || line.startsWith("OE") || line.startsWith("MR")
				|| line.startsWith("PE"))
			return "R" + line;
		if (line.equals("11"))
			return "?11";
		if (P_NORMALIZE_LINE_SPECIAL_NUMBER.matcher(line).matches())
			return "R" + line;

		final Matcher m = P_NORMALIZE_LINE.matcher(line);
		if (m.matches())
		{
			final String type = m.group(1);
			final String number = m.group(2).replace(" ", "");

			if (type.equals("ICE")) // InterCityExpress
				return "IICE" + number;
			if (type.equals("IC")) // InterCity
				return "IIC" + number;
			if (type.equals("EC")) // EuroCity
				return "IEC" + number;
			if (type.equals("EN")) // EuroNight
				return "IEN" + number;
			if (type.equals("CNL")) // CityNightLine
				return "ICNL" + number;
			if (type.equals("IR"))
				return "R" + number;
			if (type.equals("Zug"))
				return "R" + number;
			if (type.equals("ZUG"))
				return "R" + number;
			if (type.equals("D")) // D-Zug?
				return "RD" + number;
			if (type.equals("DNZ")) // unklar, aber vermutlich Russland
				return "RDNZ" + (number.equals("DNZ") ? "" : number);
			if (type.equals("KBS")) // Kursbuchstrecke
				return "RKBS" + number;
			if (type.equals("BKB")) // Buckower Kleinbahn
				return "RBKB" + number;
			if (type.equals("Ausfl")) // Umgebung Berlin
				return "RAusfl" + number;
			if (type.equals("PKP")) // Polen
				return "RPKP" + number;
			if (type.equals("S"))
				return "SS" + number;
			if (type.equals("U"))
				return "UU" + number;
			if (type.equals("Tra") || type.equals("Tram"))
				return "T" + number;
			if (type.equals("Bus"))
				return "B" + number;
			if (P_NORMALIZE_LINE_SPECIAL_BUS.matcher(type).matches()) // workaround for weird scheme BusF/526
				return "B" + line.substring(3);
			if (type.equals("Fäh"))
				return "F" + number;
			if (type.equals("F"))
				return "FF" + number;

			throw new IllegalStateException("cannot normalize type '" + type + "' number '" + number + "' line '" + line + "'");
		}

		throw new IllegalStateException("cannot normalize line '" + line + "'");
	}

	@Override
	protected char normalizeType(final String type)
	{
		throw new UnsupportedOperationException();
	}

	private static final Map<String, int[]> LINES = new HashMap<String, int[]>();

	static
	{
		LINES.put("SS1", new int[] { Color.rgb(221, 77, 174), Color.WHITE });
		LINES.put("SS2", new int[] { Color.rgb(16, 132, 73), Color.WHITE });
		LINES.put("SS25", new int[] { Color.rgb(16, 132, 73), Color.WHITE });
		LINES.put("SS3", new int[] { Color.rgb(22, 106, 184), Color.WHITE });
		LINES.put("SS41", new int[] { Color.rgb(162, 63, 48), Color.WHITE });
		LINES.put("SS42", new int[] { Color.rgb(191, 90, 42), Color.WHITE });
		LINES.put("SS45", new int[] { Color.rgb(191, 128, 55), Color.WHITE });
		LINES.put("SS46", new int[] { Color.rgb(191, 128, 55), Color.WHITE });
		LINES.put("SS47", new int[] { Color.rgb(191, 128, 55), Color.WHITE });
		LINES.put("SS5", new int[] { Color.rgb(243, 103, 23), Color.WHITE });
		LINES.put("SS7", new int[] { Color.rgb(119, 96, 176), Color.WHITE });
		LINES.put("SS75", new int[] { Color.rgb(119, 96, 176), Color.WHITE });
		LINES.put("SS8", new int[] { Color.rgb(85, 184, 49), Color.WHITE });
		LINES.put("SS85", new int[] { Color.rgb(85, 184, 49), Color.WHITE });
		LINES.put("SS9", new int[] { Color.rgb(148, 36, 64), Color.WHITE });

		LINES.put("UU1", new int[] { Color.rgb(84, 131, 47), Color.WHITE });
		LINES.put("UU2", new int[] { Color.rgb(215, 25, 16), Color.WHITE });
		LINES.put("UU3", new int[] { Color.rgb(47, 152, 154), Color.WHITE });
		LINES.put("UU4", new int[] { Color.rgb(255, 233, 42), Color.BLACK });
		LINES.put("UU5", new int[] { Color.rgb(91, 31, 16), Color.WHITE });
		LINES.put("UU55", new int[] { Color.rgb(91, 31, 16), Color.WHITE });
		LINES.put("UU6", new int[] { Color.rgb(127, 57, 115), Color.WHITE });
		LINES.put("UU7", new int[] { Color.rgb(0, 153, 204), Color.WHITE });
		LINES.put("UU8", new int[] { Color.rgb(24, 25, 83), Color.WHITE });
		LINES.put("UU9", new int[] { Color.rgb(255, 90, 34), Color.WHITE });

		LINES.put("TM1", new int[] { Color.rgb(204, 51, 0), Color.WHITE });
		LINES.put("TM2", new int[] { Color.rgb(116, 192, 67), Color.WHITE });
		LINES.put("TM4", new int[] { Color.rgb(208, 28, 34), Color.WHITE });
		LINES.put("TM5", new int[] { Color.rgb(204, 153, 51), Color.WHITE });
		LINES.put("TM6", new int[] { Color.rgb(0, 0, 255), Color.WHITE });
		LINES.put("TM8", new int[] { Color.rgb(255, 102, 0), Color.WHITE });
		LINES.put("TM10", new int[] { Color.rgb(0, 153, 51), Color.WHITE });
		LINES.put("TM13", new int[] { Color.rgb(51, 153, 102), Color.WHITE });
		LINES.put("TM17", new int[] { Color.rgb(153, 102, 51), Color.WHITE });

		LINES.put("B12", new int[] { Color.rgb(153, 102, 255), Color.WHITE });
		LINES.put("B16", new int[] { Color.rgb(0, 0, 255), Color.WHITE });
		LINES.put("B18", new int[] { Color.rgb(255, 102, 0), Color.WHITE });
		LINES.put("B21", new int[] { Color.rgb(153, 102, 255), Color.WHITE });
		LINES.put("B27", new int[] { Color.rgb(153, 102, 51), Color.WHITE });
		LINES.put("B37", new int[] { Color.rgb(153, 102, 51), Color.WHITE });
		LINES.put("B50", new int[] { Color.rgb(51, 153, 102), Color.WHITE });
		LINES.put("B60", new int[] { Color.rgb(0, 153, 51), Color.WHITE });
		LINES.put("B61", new int[] { Color.rgb(0, 153, 51), Color.WHITE });
		LINES.put("B62", new int[] { Color.rgb(0, 102, 51), Color.WHITE });
		LINES.put("B63", new int[] { Color.rgb(51, 153, 102), Color.WHITE });
		LINES.put("B67", new int[] { Color.rgb(0, 102, 51), Color.WHITE });
		LINES.put("B68", new int[] { Color.rgb(0, 153, 51), Color.WHITE });

		LINES.put("FF1", new int[] { Color.BLUE, Color.WHITE }); // Potsdam
		LINES.put("FF10", new int[] { Color.BLUE, Color.WHITE });
		LINES.put("FF11", new int[] { Color.BLUE, Color.WHITE });
		LINES.put("FF12", new int[] { Color.BLUE, Color.WHITE });
		LINES.put("FF21", new int[] { Color.BLUE, Color.WHITE });
		LINES.put("FF23", new int[] { Color.BLUE, Color.WHITE });
		LINES.put("FF24", new int[] { Color.BLUE, Color.WHITE });

		// Regional lines Brandenburg:
		LINES.put("RRE1", new int[] { Color.parseColor("#EE1C23"), Color.WHITE });
		LINES.put("RRE2", new int[] { Color.parseColor("#FFD403"), Color.BLACK });
		LINES.put("RRE3", new int[] { Color.parseColor("#F57921"), Color.WHITE });
		LINES.put("RRE4", new int[] { Color.parseColor("#952D4F"), Color.WHITE });
		LINES.put("RRE5", new int[] { Color.parseColor("#0072BC"), Color.WHITE });
		LINES.put("RRE6", new int[] { Color.parseColor("#DB6EAB"), Color.WHITE });
		LINES.put("RRE7", new int[] { Color.parseColor("#00854A"), Color.WHITE });
		LINES.put("RRE10", new int[] { Color.parseColor("#A7653F"), Color.WHITE });
		LINES.put("RRE11", new int[] { Color.parseColor("#059EDB"), Color.WHITE });
		LINES.put("RRE11", new int[] { Color.parseColor("#EE1C23"), Color.WHITE });
		LINES.put("RRE15", new int[] { Color.parseColor("#FFD403"), Color.BLACK });
		LINES.put("RRE18", new int[] { Color.parseColor("#00A65E"), Color.WHITE });
		LINES.put("RRB10", new int[] { Color.parseColor("#60BB46"), Color.WHITE });
		LINES.put("RRB12", new int[] { Color.parseColor("#A3238E"), Color.WHITE });
		LINES.put("RRB13", new int[] { Color.parseColor("#F68B1F"), Color.WHITE });
		LINES.put("RRB13", new int[] { Color.parseColor("#00A65E"), Color.WHITE });
		LINES.put("RRB14", new int[] { Color.parseColor("#A3238E"), Color.WHITE });
		LINES.put("RRB20", new int[] { Color.parseColor("#00854A"), Color.WHITE });
		LINES.put("RRB21", new int[] { Color.parseColor("#5E6DB3"), Color.WHITE });
		LINES.put("RRB22", new int[] { Color.parseColor("#0087CB"), Color.WHITE });
		LINES.put("ROE25", new int[] { Color.parseColor("#0087CB"), Color.WHITE });
		LINES.put("RNE26", new int[] { Color.parseColor("#00A896"), Color.WHITE });
		LINES.put("RNE27", new int[] { Color.parseColor("#EE1C23"), Color.WHITE });
		LINES.put("RRB30", new int[] { Color.parseColor("#00A65E"), Color.WHITE });
		LINES.put("RRB31", new int[] { Color.parseColor("#60BB46"), Color.WHITE });
		LINES.put("RMR33", new int[] { Color.parseColor("#EE1C23"), Color.WHITE });
		LINES.put("ROE35", new int[] { Color.parseColor("#5E6DB3"), Color.WHITE });
		LINES.put("ROE36", new int[] { Color.parseColor("#A7653F"), Color.WHITE });
		LINES.put("RRB43", new int[] { Color.parseColor("#5E6DB3"), Color.WHITE });
		LINES.put("RRB45", new int[] { Color.parseColor("#FFD403"), Color.BLACK });
		LINES.put("ROE46", new int[] { Color.parseColor("#DB6EAB"), Color.WHITE });
		LINES.put("RMR51", new int[] { Color.parseColor("#DB6EAB"), Color.WHITE });
		LINES.put("RRB51", new int[] { Color.parseColor("#DB6EAB"), Color.WHITE });
		LINES.put("RRB54", new int[] { Color.parseColor("#FFD403"), Color.parseColor("#333333") });
		LINES.put("RRB55", new int[] { Color.parseColor("#F57921"), Color.WHITE });
		LINES.put("ROE60", new int[] { Color.parseColor("#60BB46"), Color.WHITE });
		LINES.put("ROE63", new int[] { Color.parseColor("#FFD403"), Color.BLACK });
		LINES.put("ROE65", new int[] { Color.parseColor("#0072BC"), Color.WHITE });
		LINES.put("RRB66", new int[] { Color.parseColor("#60BB46"), Color.WHITE });
		LINES.put("RPE70", new int[] { Color.parseColor("#FFD403"), Color.BLACK });
		LINES.put("RPE73", new int[] { Color.parseColor("#00A896"), Color.WHITE });
		LINES.put("RPE74", new int[] { Color.parseColor("#0072BC"), Color.WHITE });
		LINES.put("T89", new int[] { Color.parseColor("#EE1C23"), Color.WHITE });
		LINES.put("RRB91", new int[] { Color.parseColor("#A7653F"), Color.WHITE });
		LINES.put("RRB93", new int[] { Color.parseColor("#A7653F"), Color.WHITE });
	}

	@Override
	public int[] lineColors(final String line)
	{
		final int[] lineColors = LINES.get(line);
		if (lineColors != null)
			return lineColors;
		else
			return super.lineColors(line);
	}
}
