# Reza Asad
#
# This will extract information about some restaurants in San Fransisco.
# A random walk is performed with the location of the restaurants as the  
# origin. The random walk is there to make more points representing
# people near restaurants. There will be 1000000 points generated and the
# location of the 1000000 people will get updated.
#
# Note: For better performance the producer should be parallelized. 
# I devided the 1000000 data points between 12 producers. I dedicated
# 4 of my 5 nodes on AWS for producing the messages. Hence each node
# hosting 3 producers. 
# -*- coding: utf-8 -*-


from kafka.client import KafkaClient
from kafka.producer import KeyedProducer
from kafka.partitioner import RoundRobinPartitioner
import argparse
import json
import pprint
import sys
import urllib
import urllib2
import csv
import random
import time
import oauth2


API_HOST = 'api.yelp.com'
DEFAULT_TERM = 'Restaurants'
SEARCH_LIMIT = 20
SEARCH_PATH = '/v2/search/'
BUSINESS_PATH = '/v2/business/'
kafka = KafkaClient("awsHost:9092")
producer = KeyedProducer(kafka, partitioner=RoundRobinPartitioner)


# OAuth credential placeholders that must be filled in by users.
CONSUMER_KEY = "API Key"
CONSUMER_SECRET = "API Key"
TOKEN = "API Key"
TOKEN_SECRET = "API Key"


def request(host, path, url_params=None):
    """Prepares OAuth authentication and sends the request to the API.
    Args:
        host (str): The domain host of the API.
        path (str): The path of the API after the domain.
        url_params (dict): An optional set of query parameters in the request.
    Returns:
        dict: The JSON response from the request.
    Raises:
        urllib2.HTTPError: An error occurs from the HTTP request.
    """
    url_params = url_params or {}
    url = 'http://{0}{1}?'.format(host, urllib.quote(path.encode('utf8')))

    consumer = oauth2.Consumer(CONSUMER_KEY, CONSUMER_SECRET)
    oauth_request = oauth2.Request(method="GET", url=url, parameters=url_params)

    oauth_request.update(
        {
            'oauth_nonce': oauth2.generate_nonce(),
            'oauth_timestamp': oauth2.generate_timestamp(),
            'oauth_token': TOKEN,
            'oauth_consumer_key': CONSUMER_KEY
        }
    )
    token = oauth2.Token(TOKEN, TOKEN_SECRET)
    oauth_request.sign_request(oauth2.SignatureMethod_HMAC_SHA1(), consumer, token)
    signed_url = oauth_request.to_url()
    
    #print u'Querying {0} ...'.format(url)

    conn = urllib2.urlopen(signed_url, None)
    try:
        response = json.loads(conn.read())
    finally:
        conn.close()

    return response

def search(term, location):
    """Query the Search API by a search term and location.
    Args:
        term (str): The search term passed to the API.
        location (str): The search location passed to the API.
    Returns:
        dict: The JSON response from the request.
    """
    
    url_params = {
        'term': term.replace(' ', '+'),
        'location': location.replace(' ', '+'),
        'limit': SEARCH_LIMIT
    }
    return request(API_HOST, SEARCH_PATH, url_params=url_params)

def get_business(business_id):
    """Query the Business API by a business ID.
    Args:
        business_id (str): The ID of the business to query.
    Returns:
        dict: The JSON response from the request.
    """
    business_path = BUSINESS_PATH + business_id

    return request(API_HOST, business_path)

def query_api(term, location):
    """Queries the API by the input values from the user.
    Args:
        term (str): The search term to query.
        location (str): The location of the business to query.
    """
    response = search(term, location)

    businesses = response.get('businesses')

    if not businesses:
        print u'No businesses for {0} in {1} found.'.format(term, location)
        return

    business_id = businesses[0]['id']

    print u'{0} businesses found, querying business info for the top result "{1}" ...'.format(
        len(businesses),
        business_id
    )

    response = get_business(business_id)

    #print u'Result for business "{0}" found:'.format(business_id)
    #pprint.pprint(response, indent=2)
    return businesses

def main():
	cities = ["san fransisco"]
	newPoint = {}
	DEFAULT_LOCATION = cities[0]
	parser = argparse.ArgumentParser()
	parser.add_argument('-q', '--term', dest='term', default=DEFAULT_TERM, type=str, help='Search term (default: %(default)s)')
	parser.add_argument('-l', '--location', dest='location', default=DEFAULT_LOCATION, type=str, help='Search location (default: %(default)s)')
	input_values = parser.parse_args()
	businesses = query_api(input_values.term, input_values.location)

   	while True:
   		m = 0
		for j in range(0, len(businesses)):
			newPoint["latitude"] = businesses[j]["location"]["coordinate"]["latitude"]
			newPoint["longitude"] = businesses[j]["location"]["coordinate"]["longitude"]
			for k in range(0,50000):
				latWalk = random.uniform(-0.008, 0.008)
				lonWalk = random.uniform(-0.008, 0.008)
				newPoint["latitude"] += latWalk
				newPoint["longitude"] += lonWalk
				newPoint["id"] = m
				m+=1
				response = producer.send_messages(topic, "{}".format(newPoint["id"]), json.dumps(newPoint, indent=4, separators=(',', ': ')))

if __name__ == '__main__':
    main()
