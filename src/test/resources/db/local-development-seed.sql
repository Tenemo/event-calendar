-- Executed inside a transaction by LocalDatabaseSeeder after it verifies and rebuilds the local schema.
-- Keep fixed identifiers and timestamps so each reseed produces the same application state.

do $local_seed_safety$
begin
    if current_setting('application_name') <> 'calendar-social-local-reseed' then
        raise exception 'Local development seed requires the verified reseed connection.';
    end if;
    if to_regclass('public.app_user') is null
            or to_regclass('public.calendar') is null
            or to_regclass('public.calendar_event') is null
            or to_regclass('public.calendar_membership') is null
            or to_regclass('public.registration_bootstrap') is null then
        raise exception 'Local development seed requires the calendar.social schema.';
    end if;
end
$local_seed_safety$;

truncate table app_user, calendar, registration_bootstrap restart identity cascade;

insert into app_user(id, username, display_name, password_hash, password_version) values
    (1, 'admin', 'Local admin',
        'PBKDF2WithHmacSHA256:600000:ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=:xI7oQ7nFFZ0B3d9uVkzb1GXB1RLQvYYpEVk8dPM9xDc=', 0),
    (2, 'maya', 'Maya Nowak',
        'PBKDF2WithHmacSHA256:600000:QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8=:rPPiqJDII1ElPaGFy6r9pfgnIe2c6hZn3JLs2yFB6TE=', 0),
    (3, 'tomasz', 'Tomasz Zieliński',
        'PBKDF2WithHmacSHA256:600000:YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8=:KHGGJ8gaxdaGF7xTQEbCiAm3PPciFdyX+RZCVMvCspg=', 0);

insert into calendar(
        id,
        name,
        description,
        calendar_link_token,
        time_zone,
        public_access_enabled,
        version) values
    (1,
        'Friends and birthdays',
        'Birthdays, dinners, and the plans we keep rescheduling.',
        substring(md5('calendar.social local seed friends') from 1 for 10) || 'A',
        'Europe/Warsaw',
        true,
        0),
    (2,
        'Weekend adventures',
        'Kayaking, hikes, bike rides, and weather-dependent ideas.',
        substring(md5('calendar.social local seed outdoors') from 1 for 10) || 'E',
        'Europe/Warsaw',
        true,
        0),
    (3,
        'Lisbon long weekend',
        'Flights, reservations, and loose ideas for October.',
        substring(md5('calendar.social local seed lisbon') from 1 for 10) || 'I',
        'Europe/Lisbon',
        false,
        0);

insert into calendar_membership(calendar_id, user_id, role_name) values
    (1, 1, 'ADMIN'),
    (1, 2, 'EDITOR'),
    (1, 3, 'EDITOR'),
    (2, 1, 'ADMIN'),
    (2, 3, 'EDITOR'),
    (3, 1, 'ADMIN'),
    (3, 2, 'EDITOR');

insert into calendar_event(
        id,
        calendar_id,
        title,
        description,
        location,
        start_at,
        end_at,
        all_day,
        version) values
    (1, 1,
        'Coffee with Ania',
        null,
        'Relaks, Puławska 48',
        timestamptz '2026-07-24 06:15:00+00',
        timestamptz '2026-07-24 07:00:00+00',
        false,
        0),
    (2, 1,
        'Maya''s birthday',
        'Bring the photo book and confirm the dinner booking.',
        null,
        timestamptz '2026-08-05 22:00:00+00',
        timestamptz '2026-08-06 22:00:00+00',
        true,
        0),
    (3, 1,
        'Board games at Marta''s',
        'Start with Heat, then switch to shorter games if everyone is tired.',
        'Marta''s place, Żoliborz',
        timestamptz '2026-08-14 17:00:00+00',
        timestamptz '2026-08-14 22:30:00+00',
        false,
        0),
    (4, 1,
        'Late-summer picnic',
        'Blankets, lemonade, fruit, and something that survives the tram ride.',
        'Pole Mokotowskie',
        timestamptz '2026-08-23 10:00:00+00',
        timestamptz '2026-08-23 15:00:00+00',
        false,
        0),
    (5, 1,
        'Kasia and Adam''s wedding weekend',
        'Friday arrival, Saturday ceremony, and a slow Sunday breakfast.',
        'Kazimierz Dolny',
        timestamptz '2026-09-03 22:00:00+00',
        timestamptz '2026-09-06 22:00:00+00',
        true,
        0),
    (6, 2,
        'Sunrise paddle',
        'Quiet water before work. Cancel if wind exceeds 20 km/h.',
        'Port Czerniakowski',
        timestamptz '2026-07-30 02:45:00+00',
        timestamptz '2026-07-30 06:00:00+00',
        false,
        0),
    (7, 2,
        'Wda kayaking day',
        'Meet by the station, rent boats on arrival, and pack lunch in dry bags.',
        'Czarna Woda station',
        timestamptz '2026-08-01 05:00:00+00',
        timestamptz '2026-08-01 17:30:00+00',
        false,
        0),
    (8, 2,
        'Climbing gym backup plan',
        null,
        'Murall Warszawa',
        timestamptz '2026-08-02 14:00:00+00',
        timestamptz '2026-08-02 16:00:00+00',
        false,
        0),
    (9, 2,
        'Bieszczady hiking weekend',
        'Base in Wetlina. Routes depend on weather and how everyone feels after Friday.',
        'Wetlina',
        timestamptz '2026-08-27 22:00:00+00',
        timestamptz '2026-08-30 22:00:00+00',
        true,
        0),
    (10, 2,
        'Bike ride to Czersk',
        'Easy pace with a bakery stop on the way back.',
        'Start at Metro Kabaty',
        timestamptz '2026-09-12 06:30:00+00',
        timestamptz '2026-09-12 13:00:00+00',
        false,
        0),
    (11, 3,
        'Flight to Lisbon',
        'Departure is 08:20 Warsaw time. Check in online the evening before.',
        'WAW to LIS',
        timestamptz '2026-10-08 06:20:00+00',
        timestamptz '2026-10-08 09:25:00+00',
        false,
        0),
    (12, 3,
        'Apartment check-in',
        'Message the host after leaving the airport.',
        'Alfama, Lisbon',
        timestamptz '2026-10-08 14:00:00+00',
        timestamptz '2026-10-08 14:30:00+00',
        false,
        0),
    (13, 3,
        'Dinner at O Velho Eurico',
        'Reservation is under Maya. Arrive ten minutes early.',
        'Largo São Cristóvão 3',
        timestamptz '2026-10-09 19:30:00+00',
        timestamptz '2026-10-09 21:30:00+00',
        false,
        0),
    (14, 3,
        'Sintra day trip',
        'Take the early train and choose one palace rather than rushing through three.',
        'Sintra',
        timestamptz '2026-10-09 23:00:00+00',
        timestamptz '2026-10-10 23:00:00+00',
        true,
        0),
    (15, 3,
        'Flight home',
        'Allow extra time for the metro and airport security.',
        'LIS to WAW',
        timestamptz '2026-10-12 10:10:00+00',
        timestamptz '2026-10-12 14:00:00+00',
        false,
        0);

insert into registration_bootstrap(singleton_id, consumed_at) values
    (1, timestamptz '2026-07-01 10:00:00+00');

select setval(pg_get_serial_sequence('app_user', 'id'), 3, true);
select setval(pg_get_serial_sequence('calendar', 'id'), 3, true);
select setval(pg_get_serial_sequence('calendar_event', 'id'), 15, true);
select setval(pg_get_serial_sequence('invitation', 'id'), 1, false);
