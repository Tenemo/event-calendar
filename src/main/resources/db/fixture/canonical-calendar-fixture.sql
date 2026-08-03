-- Installed inside a caller-managed transaction after Java supplies validated, environment-specific
-- account hashes and bearer tokens through transaction-local PostgreSQL settings.

do $canonical_fixture_safety$
declare
    fixture_scope text := current_setting('calendar.fixture.scope', true);
    owner_username text := current_setting('calendar.fixture.owner_username', true);
    maya_username text := current_setting('calendar.fixture.maya_username', true);
    tomasz_username text := current_setting('calendar.fixture.tomasz_username', true);
    owner_exists boolean := current_setting('calendar.fixture.owner_exists', true)::boolean;
    maya_exists boolean := current_setting('calendar.fixture.maya_exists', true)::boolean;
    tomasz_exists boolean := current_setting('calendar.fixture.tomasz_exists', true)::boolean;
begin
    if fixture_scope not in ('local', 'preview', 'production') then
        raise exception 'Canonical calendar fixture scope is invalid.';
    end if;
    if fixture_scope = 'local'
            and current_setting('application_name') <> 'calendar-social-local-reseed' then
        raise exception 'Local fixture installation requires the verified reseed connection.';
    end if;
    if to_regclass('public.app_user') is null
            or to_regclass('public.calendar') is null
            or to_regclass('public.calendar_event') is null
            or to_regclass('public.calendar_membership') is null
            or to_regclass('public.invitation') is null
            or to_regclass('public.registration_bootstrap') is null then
        raise exception 'Canonical calendar fixture requires the calendar.social schema.';
    end if;
    if (select count(*) from registration_bootstrap where singleton_id = 1) <> 1 then
        raise exception 'Canonical calendar fixture requires the singleton registration bootstrap state.';
    end if;
    if owner_exists <> exists (select 1 from app_user where username = owner_username)
            or maya_exists <> exists (select 1 from app_user where username = maya_username)
            or tomasz_exists <> exists (select 1 from app_user where username = tomasz_username) then
        raise exception 'Canonical calendar fixture account existence does not match the verified preflight.';
    end if;
    if exists (
            select 1
            from calendar
            where calendar_link_token in (
                current_setting('calendar.fixture.friends_token', true),
                current_setting('calendar.fixture.weekend_token', true),
                current_setting('calendar.fixture.lisbon_token', true))) then
        raise exception 'Canonical calendar fixture bearer links already exist.';
    end if;
end
$canonical_fixture_safety$;

insert into app_user(username, display_name, password_hash, password_version)
select
    current_setting('calendar.fixture.owner_username', true),
    current_setting('calendar.fixture.owner_display_name', true),
    current_setting('calendar.fixture.owner_password_hash', true),
    0
where not current_setting('calendar.fixture.owner_exists', true)::boolean;

insert into app_user(username, display_name, password_hash, password_version)
select
    current_setting('calendar.fixture.maya_username', true),
    'Maya Nowak',
    current_setting('calendar.fixture.maya_password_hash', true),
    0
where not current_setting('calendar.fixture.maya_exists', true)::boolean;

insert into app_user(username, display_name, password_hash, password_version)
select
    current_setting('calendar.fixture.tomasz_username', true),
    'Tomasz Zieliński',
    current_setting('calendar.fixture.tomasz_password_hash', true),
    0
where not current_setting('calendar.fixture.tomasz_exists', true)::boolean;

insert into calendar(
        name,
        description,
        calendar_link_token,
        time_zone,
        public_access_enabled,
        version) values
    (
        'Friends and birthdays',
        'Birthdays, dinners, and the plans we keep rescheduling.',
        current_setting('calendar.fixture.friends_token', true),
        'Europe/Warsaw',
        true,
        0
    ),
    (
        'Weekend adventures',
        'Kayaking, hikes, bike rides, and weather-dependent ideas.',
        current_setting('calendar.fixture.weekend_token', true),
        'Europe/Warsaw',
        true,
        0
    ),
    (
        'Lisbon long weekend',
        'Flights, reservations, and loose ideas for October.',
        current_setting('calendar.fixture.lisbon_token', true),
        'Europe/Lisbon',
        false,
        0
    );

with fixture_calendars(calendar_key, calendar_id) as (
    select fixture_calendar.calendar_key, calendar.id
    from (values
        ('friends', current_setting('calendar.fixture.friends_token', true)),
        ('weekend', current_setting('calendar.fixture.weekend_token', true)),
        ('lisbon', current_setting('calendar.fixture.lisbon_token', true))
    ) as fixture_calendar(calendar_key, calendar_link_token)
    join calendar using (calendar_link_token)
), fixture_users(user_key, user_id) as (
    select fixture_user.user_key, app_user.id
    from (values
        ('owner', current_setting('calendar.fixture.owner_username', true)),
        ('maya', current_setting('calendar.fixture.maya_username', true)),
        ('tomasz', current_setting('calendar.fixture.tomasz_username', true))
    ) as fixture_user(user_key, username)
    join app_user using (username)
)
insert into calendar_membership(calendar_id, user_id, role_name)
select fixture_calendars.calendar_id, fixture_users.user_id, fixture_membership.role_name
from (values
    ('friends', 'owner', 'ADMIN'),
    ('friends', 'maya', 'EDITOR'),
    ('friends', 'tomasz', 'EDITOR'),
    ('weekend', 'owner', 'ADMIN'),
    ('weekend', 'tomasz', 'EDITOR'),
    ('lisbon', 'owner', 'ADMIN'),
    ('lisbon', 'maya', 'EDITOR')
) as fixture_membership(calendar_key, user_key, role_name)
join fixture_calendars using (calendar_key)
join fixture_users using (user_key);

with fixture_calendars(calendar_key, calendar_id) as (
    select fixture_calendar.calendar_key, calendar.id
    from (values
        ('friends', current_setting('calendar.fixture.friends_token', true)),
        ('weekend', current_setting('calendar.fixture.weekend_token', true)),
        ('lisbon', current_setting('calendar.fixture.lisbon_token', true))
    ) as fixture_calendar(calendar_key, calendar_link_token)
    join calendar using (calendar_link_token)
)
insert into calendar_event(
        calendar_id,
        title,
        description,
        location,
        start_at,
        end_at,
        all_day,
        version)
select
    fixture_calendars.calendar_id,
    fixture_event.title,
    fixture_event.description,
    fixture_event.location,
    fixture_event.start_at,
    fixture_event.end_at,
    fixture_event.all_day,
    0
from (values
    ('friends',
        'Coffee with Ania',
        null,
        'Relaks, Puławska 48',
        timestamptz '2026-07-24 06:15:00+00',
        timestamptz '2026-07-24 07:00:00+00',
        false),
    ('friends',
        'Maya''s birthday',
        'Bring the photo book and confirm the dinner booking.',
        null,
        timestamptz '2026-08-05 22:00:00+00',
        timestamptz '2026-08-06 22:00:00+00',
        true),
    ('friends',
        'Board games at Marta''s',
        'Start with Heat, then switch to shorter games if everyone is tired.',
        'Marta''s place, Żoliborz',
        timestamptz '2026-08-14 17:00:00+00',
        timestamptz '2026-08-14 22:30:00+00',
        false),
    ('friends',
        'Late-summer picnic',
        'Blankets, lemonade, fruit, and something that survives the tram ride.',
        'Pole Mokotowskie',
        timestamptz '2026-08-23 10:00:00+00',
        timestamptz '2026-08-23 15:00:00+00',
        false),
    ('friends',
        'Kasia and Adam''s wedding weekend',
        'Friday arrival, Saturday ceremony, and a slow Sunday breakfast.',
        'Kazimierz Dolny',
        timestamptz '2026-09-03 22:00:00+00',
        timestamptz '2026-09-06 22:00:00+00',
        true),
    ('weekend',
        'Sunrise paddle',
        'Quiet water before work. Cancel if wind exceeds 20 km/h.',
        'Port Czerniakowski',
        timestamptz '2026-07-30 02:45:00+00',
        timestamptz '2026-07-30 06:00:00+00',
        false),
    ('weekend',
        'Wda kayaking day',
        'Meet by the station, rent boats on arrival, and pack lunch in dry bags.',
        'Czarna Woda station',
        timestamptz '2026-08-01 05:00:00+00',
        timestamptz '2026-08-01 17:30:00+00',
        false),
    ('weekend',
        'Climbing gym backup plan',
        null,
        'Murall Warszawa',
        timestamptz '2026-08-02 14:00:00+00',
        timestamptz '2026-08-02 16:00:00+00',
        false),
    ('weekend',
        'Bieszczady hiking weekend',
        'Base in Wetlina. Routes depend on weather and how everyone feels after Friday.',
        'Wetlina',
        timestamptz '2026-08-27 22:00:00+00',
        timestamptz '2026-08-30 22:00:00+00',
        true),
    ('weekend',
        'Bike ride to Czersk',
        'Easy pace with a bakery stop on the way back.',
        'Start at Metro Kabaty',
        timestamptz '2026-09-12 06:30:00+00',
        timestamptz '2026-09-12 13:00:00+00',
        false),
    ('lisbon',
        'Flight to Lisbon',
        'Departure is 08:20 Warsaw time. Check in online the evening before.',
        'WAW to LIS',
        timestamptz '2026-10-08 06:20:00+00',
        timestamptz '2026-10-08 09:25:00+00',
        false),
    ('lisbon',
        'Apartment check-in',
        'Message the host after leaving the airport.',
        'Alfama, Lisbon',
        timestamptz '2026-10-08 14:00:00+00',
        timestamptz '2026-10-08 14:30:00+00',
        false),
    ('lisbon',
        'Dinner at O Velho Eurico',
        'Reservation is under Maya. Arrive ten minutes early.',
        'Largo São Cristóvão 3',
        timestamptz '2026-10-09 19:30:00+00',
        timestamptz '2026-10-09 21:30:00+00',
        false),
    ('lisbon',
        'Sintra day trip',
        'Take the early train and choose one palace rather than rushing through three.',
        'Sintra',
        timestamptz '2026-10-09 23:00:00+00',
        timestamptz '2026-10-10 23:00:00+00',
        true),
    ('lisbon',
        'Flight home',
        'Allow extra time for the metro and airport security.',
        'LIS to WAW',
        timestamptz '2026-10-12 10:10:00+00',
        timestamptz '2026-10-12 14:00:00+00',
        false),
    ('weekend',
        'Vistula gravel loop',
        'Check tire pressure the night before and keep the train as a bail-out option.',
        'Start at Metro Młociny',
        timestamptz '2026-09-26 06:00:00+00',
        timestamptz '2026-09-26 13:30:00+00',
        false),
    ('weekend',
        'Autumn mushroom walk',
        'Bring baskets, a field guide, and lunch. We only take mushrooms everyone can identify.',
        'Kampinos National Park',
        timestamptz '2026-10-11 06:30:00+00',
        timestamptz '2026-10-11 11:00:00+00',
        false),
    ('weekend',
        'Cabin weekend in Mazury',
        'Split groceries before departure, collect firewood on Friday, and keep Sunday unplanned.',
        'Ruciane-Nida',
        timestamptz '2026-10-22 22:00:00+00',
        timestamptz '2026-10-25 23:00:00+00',
        true),
    ('weekend',
        'Indoor bouldering evening',
        'Bad-weather fallback with an easy dinner nearby afterwards.',
        'Murall Annopol',
        timestamptz '2026-11-05 17:30:00+00',
        timestamptz '2026-11-05 20:00:00+00',
        false),
    ('weekend',
        'Independence Day forest hike',
        'An 18 km loop with a hot soup stop. Pack a headlamp in case the final section runs late.',
        'Palmiry',
        timestamptz '2026-11-11 08:00:00+00',
        timestamptz '2026-11-11 14:30:00+00',
        false),
    ('weekend',
        'Thermos picnic at Zegrze',
        'Confirm the wind and rain forecast that morning. Bring something warm to share.',
        'Nieporęt lakeside',
        timestamptz '2026-11-29 10:00:00+00',
        timestamptz '2026-11-29 13:30:00+00',
        false),
    ('weekend',
        'First snow cross-country trial',
        'Rent skis in advance if the snow holds; otherwise turn it into a winter walk.',
        'Julinek',
        timestamptz '2026-12-12 08:30:00+00',
        timestamptz '2026-12-12 12:00:00+00',
        false),
    ('weekend',
        'Winter solstice bonfire',
        'Bring dry wood, soup in flasks, and lights for the walk back after sunset.',
        'Polana Opaleń',
        timestamptz '2026-12-20 14:30:00+00',
        timestamptz '2026-12-20 18:00:00+00',
        false),
    ('weekend',
        'New Year walk and hot chocolate',
        'A gentle loop, leftovers at the halfway point, and no ambitious start time.',
        'Skaryszewski Park',
        timestamptz '2027-01-03 10:00:00+00',
        timestamptz '2027-01-03 13:00:00+00',
        false),
    ('weekend',
        'Skiing in Italy',
        'Saturday transfer, five ski days, one rest day, and the return trip the following Saturday.',
        'Val di Sole, Italy',
        timestamptz '2027-01-15 23:00:00+00',
        timestamptz '2027-01-23 23:00:00+00',
        true)
) as fixture_event(
        calendar_key,
        title,
        description,
        location,
        start_at,
        end_at,
        all_day)
join fixture_calendars using (calendar_key);

update registration_bootstrap
set consumed_at = coalesce(
    consumed_at,
    current_setting('calendar.fixture.bootstrap_consumed_at', true)::timestamptz)
where singleton_id = 1;
